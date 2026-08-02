package com.mola.cmd.proxy.app.acp.channel.wecom;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.channel.ChannelAdapter;
import com.mola.cmd.proxy.app.acp.channel.ChannelConfigFileStore;
import com.mola.cmd.proxy.app.acp.channel.ChannelTalkToBridge;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelConfig;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelEvent;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelReplyRoute;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelSendResult;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelStatus;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Java 8 WeCom intelligent-bot WebSocket adapter. */
public final class WeComChannelAdapter extends WebSocketListener implements ChannelAdapter {
    private static final Logger logger = LoggerFactory.getLogger(WeComChannelAdapter.class);
    private static final long HEARTBEAT_MS = 30_000L;
    private static final long REQUEST_TIMEOUT_MS = 10_000L;
    private static final long ROUTE_TTL_MS = 24L * 60L * 60L * 1000L;
    private static final long DEDUP_TTL_MS = 24L * 60L * 60L * 1000L;
    private static final int DEDUP_MAX = 10_000;
    private static final int[] RECONNECT_SECONDS = {1, 2, 4, 8, 16, 30};

    private final ChannelConfig config;
    private final ChannelTalkToBridge bridge;
    private final OkHttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<ChannelStatus> status =
            new AtomicReference<>(ChannelStatus.STOPPED);
    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private final AtomicBoolean displaced = new AtomicBoolean(false);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
    private final ConcurrentHashMap<String, CompletableFuture<WeComFrame>> pending =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> seenMessages = new ConcurrentHashMap<>();
    private volatile WebSocket socket;
    private volatile ScheduledFuture<?> heartbeat;
    private volatile long lastAckAt;
    private volatile String subscribeRequestId;

    public WeComChannelAdapter(ChannelConfig config, ChannelTalkToBridge bridge) {
        this.config = config;
        this.bridge = bridge;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "wecom-channel-" + config.getId());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    @Override public String getChannelId() { return config.getId(); }
    @Override public ChannelStatus getStatus() { return status.get(); }

    @Override
    public void start() {
        if (!stopped.compareAndSet(true, false)) return;
        displaced.set(false);
        reconnectAttempt.set(0);
        connect(false);
    }

    private void connect(boolean reconnect) {
        if (stopped.get() || displaced.get()) return;
        reconnectScheduled.set(false);
        status.set(reconnect ? ChannelStatus.RECONNECTING : ChannelStatus.CONNECTING);
        String url = blank(config.getWsUrl())
                ? "wss://openws.work.weixin.qq.com" : config.getWsUrl();
        Request request = new Request.Builder().url(url).build();
        socket = httpClient.newWebSocket(request, this);
        logger.info("channel connecting: channelId={}, type=WECOM_WS, status={}",
                config.getId(), status.get());
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        if (stopped.get()) {
            webSocket.close(1000, "stopped");
            return;
        }
        socket = webSocket;
        status.set(ChannelStatus.AUTHENTICATING);
        subscribeRequestId = WeComProtocol.requestId("subscribe");
        webSocket.send(WeComProtocol.subscribe(
                subscribeRequestId, config.getBotId(), config.getSecret()));
        final String expectedRequestId = subscribeRequestId;
        scheduler.schedule(() -> {
            if (!stopped.get() && expectedRequestId.equals(subscribeRequestId)
                    && status.compareAndSet(ChannelStatus.AUTHENTICATING, ChannelStatus.RECONNECTING)) {
                logger.warn("channel auth timeout: channelId={}, errorCode=CHANNEL_AUTH_FAILED",
                        config.getId());
                WebSocket current = socket;
                if (current != null) current.cancel();
            }
        }, REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        WeComFrame frame;
        try {
            frame = WeComProtocol.parse(text);
        } catch (RuntimeException e) {
            logger.warn("channel invalid frame: channelId={}, errorCode=CHANNEL_FRAME_INVALID",
                    config.getId());
            return;
        }
        lastAckAt = System.currentTimeMillis();
        if (WeComProtocol.MESSAGE_CALLBACK.equals(frame.getCmd())) {
            handleMessage(frame);
            return;
        }
        if (WeComProtocol.EVENT_CALLBACK.equals(frame.getCmd())) {
            handleEvent(frame);
            return;
        }
        CompletableFuture<WeComFrame> future = pending.remove(frame.getRequestId());
        if (future != null) future.complete(frame);
        if (subscribeRequestId != null && subscribeRequestId.equals(frame.getRequestId())) {
            if (frame.isSuccess()) {
                status.set(ChannelStatus.CONNECTED);
                reconnectAttempt.set(0);
                subscribeRequestId = null;
                startHeartbeat();
                logger.info("channel authenticated: channelId={}, status=CONNECTED", config.getId());
            } else {
                status.set(ChannelStatus.ERROR);
                displaced.set(true);
                logger.error("channel auth failed: channelId={}, errorCode=CHANNEL_AUTH_FAILED, errcode={}",
                        config.getId(), frame.getErrorCode());
                webSocket.close(1008, "authentication failed");
            }
        }
    }

    @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
        onMessage(webSocket, bytes.utf8());
    }

    private void handleMessage(WeComFrame frame) {
        JsonObject body = frame.getBody();
        String msgId = WeComProtocol.string(body, "msgid");
        if (!markFirst(msgId)) {
            logger.info("channel duplicate ignored: channelId={}, msgid={}, errorCode=DUPLICATE_EVENT",
                    config.getId(), msgId);
            return;
        }
        String msgType = WeComProtocol.string(body, "msgtype");
        if (!"text".equals(msgType)) {
            logger.info("channel non-text ignored: channelId={}, msgid={}, msgtype={}",
                    config.getId(), msgId, msgType);
            return;
        }
        JsonObject text = object(body, "text");
        JsonObject from = object(body, "from");
        String content = WeComProtocol.string(text, "content");
        String userId = WeComProtocol.string(from, "userid");
        String displayName = firstNonBlank(
                WeComProtocol.string(from, "name"),
                WeComProtocol.string(from, "alias"), userId);
        String chatId = WeComProtocol.string(body, "chatid");
        String chatType = WeComProtocol.string(body, "chattype");
        if (blank(content) || blank(userId) || blank(frame.getRequestId())) {
            logger.warn("channel text missing fields: channelId={}, msgid={}, errorCode=CHANNEL_FRAME_INVALID",
                    config.getId(), msgId);
            return;
        }
        captureDefaultGroupChatId(chatType, chatId);
        ChannelReplyRoute route = new ChannelReplyRoute(
                frame.getRequestId(), msgId, userId, chatId, chatType,
                System.currentTimeMillis() + ROUTE_TTL_MS);
        try {
            TalkToDispatcher.InboundDeliveryResult result = bridge.onEvent(new ChannelEvent(
                    config.getId(), msgId, userId, displayName, content, route));
            logger.info("channel inbound delivered: channelId={}, msgid={}, delivery={}",
                    config.getId(), msgId, result.getStatus().name().toLowerCase());
        } catch (RuntimeException e) {
            logger.error("channel inbound failed: channelId={}, msgid={}, errorCode=ACP_BINDING_NOT_FOUND",
                    config.getId(), msgId, e);
        }
    }

    private void captureDefaultGroupChatId(String chatType, String chatId) {
        if (!"group".equals(chatType) || blank(chatId)
                || !blank(config.getDefaultChatId())) return;
        try {
            String effective = ChannelConfigFileStore.fillDefaultChatIdIfBlank(
                    config.getId(), chatId);
            config.setDefaultChatId(effective);
            logger.info("channel default chat captured: channelId={}, chatType=group, chatId={}",
                    config.getId(), effective);
        } catch (Exception e) {
            logger.error("channel default chat persist failed: channelId={}, errorCode=CHANNEL_CONFIG_WRITE_FAILED",
                    config.getId(), e);
        }
    }

    private void handleEvent(WeComFrame frame) {
        JsonObject body = frame.getBody();
        JsonObject event = object(body, "event");
        String eventType = firstNonBlank(
                WeComProtocol.string(event, "eventtype"),
                WeComProtocol.string(event, "event_type"),
                WeComProtocol.string(body, "eventtype"));
        logger.info("channel event: channelId={}, eventType={}", config.getId(), eventType);
        if ("disconnected_event".equals(eventType)) {
            displaced.set(true);
            status.set(ChannelStatus.ERROR);
            cancelHeartbeat();
            WebSocket current = socket;
            if (current != null) current.close(1000, "displaced by another connection");
            logger.error("channel displaced: channelId={}, errorCode=CHANNEL_DISCONNECTED",
                    config.getId());
        }
    }

    private boolean markFirst(String msgId) {
        if (blank(msgId)) return true;
        long now = System.currentTimeMillis();
        if (seenMessages.size() >= DEDUP_MAX) cleanupDedup(now, true);
        Long previous = seenMessages.putIfAbsent(msgId, now);
        while (previous != null && now - previous > DEDUP_TTL_MS
                && !seenMessages.replace(msgId, previous, now)) {
            previous = seenMessages.get(msgId);
        }
        cleanupDedup(now, false);
        return previous == null || now - previous > DEDUP_TTL_MS;
    }

    private void cleanupDedup(long now, boolean forceOne) {
        seenMessages.entrySet().removeIf(entry -> now - entry.getValue() > DEDUP_TTL_MS);
        if (forceOne && seenMessages.size() >= DEDUP_MAX) {
            Map.Entry<String, Long> oldest = null;
            for (Map.Entry<String, Long> entry : seenMessages.entrySet()) {
                if (oldest == null || entry.getValue() < oldest.getValue()) oldest = entry;
            }
            if (oldest != null) seenMessages.remove(oldest.getKey(), oldest.getValue());
        }
    }

    @Override
    public ChannelSendResult send(ChannelReplyRoute route, String markdown) {
        if (route == null) return ChannelSendResult.failure("reply route missing");
        if (!blank(route.getRequestId())) {
            String streamId = WeComProtocol.requestId("stream");
            ChannelSendResult passive = sendAndAwait(
                    route.getRequestId(),
                    WeComProtocol.respondMarkdown(route.getRequestId(), streamId, markdown),
                    "passive");
            if (passive.isSuccess()) return passive;
        }
        String chatId = firstNonBlank(route.getChatId(), route.getUserId());
        return blank(chatId) ? ChannelSendResult.failure("reply address missing")
                : sendProactive(chatId, markdown);
    }

    @Override
    public ChannelSendResult sendProactive(String chatId, String markdown) {
        if (blank(chatId)) return ChannelSendResult.failure("chatId missing");
        String requestId = WeComProtocol.requestId("send");
        return sendAndAwait(requestId,
                WeComProtocol.sendMarkdown(requestId, chatId, markdown), "proactive");
    }

    private ChannelSendResult sendAndAwait(String requestId, String payload, String mode) {
        if (status.get() != ChannelStatus.CONNECTED || socket == null) {
            return ChannelSendResult.failure("channel is not connected");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            CompletableFuture<WeComFrame> future = new CompletableFuture<>();
            pending.put(requestId, future);
            if (!socket.send(payload)) {
                pending.remove(requestId, future);
                continue;
            }
            try {
                WeComFrame ack = future.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (ack.isSuccess()) return ChannelSendResult.success(mode);
                return ChannelSendResult.failure("errcode=" + ack.getErrorCode()
                        + ", errmsg=" + ack.getErrorMessage());
            } catch (TimeoutException e) {
                pending.remove(requestId, future);
            } catch (InterruptedException e) {
                pending.remove(requestId, future);
                Thread.currentThread().interrupt();
                return ChannelSendResult.failure("send interrupted");
            } catch (ExecutionException e) {
                pending.remove(requestId, future);
            }
        }
        return ChannelSendResult.failure("ack timeout");
    }

    private void startHeartbeat() {
        cancelHeartbeat();
        lastAckAt = System.currentTimeMillis();
        heartbeat = scheduler.scheduleAtFixedRate(() -> {
            if (stopped.get() || status.get() != ChannelStatus.CONNECTED) return;
            if (System.currentTimeMillis() - lastAckAt > HEARTBEAT_MS * 2) {
                WebSocket current = socket;
                if (current != null) current.cancel();
                return;
            }
            String requestId = WeComProtocol.requestId("ping");
            WebSocket current = socket;
            if (current != null) current.send(WeComProtocol.ping(requestId));
        }, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        webSocket.close(code, reason);
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        socket = null;
        cancelHeartbeat();
        failPending("connection closed");
        scheduleReconnect();
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable throwable, Response response) {
        socket = null;
        cancelHeartbeat();
        failPending("connection failed");
        logger.warn("channel disconnected: channelId={}, errorCode=CHANNEL_DISCONNECTED, retryable={}",
                config.getId(), !stopped.get() && !displaced.get());
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (stopped.get() || displaced.get() || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        int attempt = reconnectAttempt.getAndIncrement();
        int delay = RECONNECT_SECONDS[Math.min(attempt, RECONNECT_SECONDS.length - 1)];
        status.set(ChannelStatus.RECONNECTING);
        scheduler.schedule(() -> connect(true), delay, TimeUnit.SECONDS);
    }

    private void failPending(String message) {
        for (Map.Entry<String, CompletableFuture<WeComFrame>> entry : pending.entrySet()) {
            if (pending.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().completeExceptionally(new IllegalStateException(message));
            }
        }
    }

    @Override
    public void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        status.set(ChannelStatus.STOPPED);
        cancelHeartbeat();
        failPending("channel stopped");
        WebSocket current = socket;
        socket = null;
        if (current != null) current.close(1000, "stopped");
        scheduler.shutdownNow();
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> current = heartbeat;
        heartbeat = null;
        if (current != null) current.cancel(false);
    }

    private static JsonObject object(JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonObject()
                ? parent.getAsJsonObject(name) : new JsonObject();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (!blank(value)) return value;
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
