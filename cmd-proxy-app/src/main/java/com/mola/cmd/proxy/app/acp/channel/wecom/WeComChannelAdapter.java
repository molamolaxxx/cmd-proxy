package com.mola.cmd.proxy.app.acp.channel.wecom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.channel.ChannelAdapter;
import com.mola.cmd.proxy.app.acp.channel.ChannelConfigFileStore;
import com.mola.cmd.proxy.app.acp.channel.ChannelTalkToBridge;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelConfig;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelEvent;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelReplyRoute;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelSendResult;
import com.mola.cmd.proxy.app.acp.mcpauth.McpAuthManager;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
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
    private final ExecutorService inboundExecutor;
    private final WeComInboundMessageParser messageParser = new WeComInboundMessageParser();
    private final WeComMediaDownloader mediaDownloader;
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
        this.inboundExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "wecom-inbound-" + config.getId());
                thread.setDaemon(true);
                return thread;
            }
        }, new ThreadPoolExecutor.AbortPolicy());
        this.mediaDownloader = new WeComMediaDownloader(httpClient);
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
        try {
            inboundExecutor.submit(() -> processMessage(frame));
        } catch (RejectedExecutionException e) {
            if (!blank(msgId)) seenMessages.remove(msgId);
            logger.warn("channel inbound queue full: channelId={}, msgid={}, errorCode=CHANNEL_INBOUND_BUSY",
                    config.getId(), msgId);
        }
    }

    private void processMessage(WeComFrame frame) {
        JsonObject body = frame.getBody();
        String msgId = WeComProtocol.string(body, "msgid");
        String msgType = WeComProtocol.string(body, "msgtype");
        String chatType = WeComProtocol.string(body, "chattype");
        if (!bridge.isInboundAllowed(config.getId(), chatType)) {
            logger.info("channel inbound ignored by policy: channelId={}, msgid={}, chatType={}, errorCode=CHANNEL_INBOUND_DISABLED",
                    config.getId(), msgId, chatType);
            return;
        }
        JsonObject from = object(body, "from");
        String userId = WeComProtocol.string(from, "userid");
        String senderName = firstNonBlank(
                WeComProtocol.string(from, "name"),
                WeComProtocol.string(from, "alias"));
        String displayName = firstNonBlank(senderName, userId);
        McpAuthManager.getInstance().recordPrincipal(
                new com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext(
                        userId, displayName, "WECOM", config.getId()));
        String chatId = WeComProtocol.string(body, "chatid");
        if (blank(msgId) || blank(userId) || blank(frame.getRequestId())) {
            logger.warn("channel message missing fields: channelId={}, msgid={}, errorCode=CHANNEL_FRAME_INVALID",
                    config.getId(), msgId);
            return;
        }
        rememberKnownChatTarget(body, chatType, chatId, userId, senderName);
        WeComInboundMessageParser.ParsedMessage parsed;
        try {
            parsed = messageParser.parse(body, mediaDownloader);
        } catch (Exception e) {
            seenMessages.remove(msgId);
            logger.warn("channel media processing failed: channelId={}, msgid={}, msgtype={}, errorCode=CHANNEL_MEDIA_FAILED",
                    config.getId(), msgId, msgType, e);
            return;
        }
        if (parsed == null) {
            logger.info("channel message type ignored: channelId={}, msgid={}, msgtype={}",
                    config.getId(), msgId, msgType);
            return;
        }
        if (blank(parsed.getText()) && parsed.getAttachments().isEmpty()) {
            logger.warn("channel message has no readable content: channelId={}, msgid={}, msgtype={}",
                    config.getId(), msgId, msgType);
            return;
        }
        ChannelReplyRoute route = new ChannelReplyRoute(
                frame.getRequestId(), msgId, userId, chatId, chatType,
                System.currentTimeMillis() + ROUTE_TTL_MS);
        try {
            TalkToDispatcher.InboundDeliveryResult result = bridge.onEvent(new ChannelEvent(
                    config.getId(), msgId, userId, displayName, parsed.getMessageType(),
                    parsed.getText(), parsed.getQuote(), parsed.getAttachments(), route));
            if (result.getStatus() == TalkToDispatcher.InboundDeliveryResult.Status.REJECTED
                    && result.getReason() != null
                    && result.getReason().startsWith("attachment staging failed")) {
                seenMessages.remove(msgId);
            }
            logger.info("channel inbound delivered: channelId={}, msgid={}, delivery={}",
                    config.getId(), msgId, result.getStatus().name().toLowerCase());
        } catch (RuntimeException e) {
            logger.error("channel inbound failed: channelId={}, msgid={}, errorCode=ACP_BINDING_NOT_FOUND",
                    config.getId(), msgId, e);
        }
    }

    private void rememberKnownChatTarget(JsonObject body, String chatType, String chatId,
                                         String userId, String senderName) {
        String targetId = "group".equals(chatType) ? chatId : userId;
        if (blank(targetId)) return;
        String displayName = discoveredDisplayName(
                body, chatType, senderName, userId, shortMessagePreview(body));
        try {
            if (ChannelConfigFileStore.recordKnownChatTarget(
                    config.getId(), targetId, displayName, chatType)) {
                logger.info("channel proactive target discovered: channelId={}, chatType={}, targetId={}",
                        config.getId(), chatType, targetId);
            }
        } catch (Exception e) {
            logger.warn("channel proactive target persistence failed: channelId={}, chatType={}, targetId={}",
                    config.getId(), chatType, targetId, e);
        }
    }

    static String discoveredDisplayName(JsonObject body, String chatType,
                                        String senderName, String userId,
                                        String messagePreview) {
        String previewLabel = blank(messagePreview) ? "" : "消息：" + messagePreview;
        return "group".equals(chatType)
                ? firstNonBlank(WeComProtocol.string(body, "chatname"),
                        WeComProtocol.string(body, "chat_name"), previewLabel, "未提供群名")
                : firstNonBlank(senderName, previewLabel, userId);
    }

    static String shortMessagePreview(JsonObject body) {
        String type = WeComProtocol.string(body, "msgtype");
        String content = "";
        if ("text".equals(type)) {
            content = WeComProtocol.string(object(body, "text"), "content");
        } else if ("voice".equals(type)) {
            content = WeComProtocol.string(object(body, "voice"), "content");
        } else if ("mixed".equals(type)) {
            StringBuilder mixedText = new StringBuilder();
            JsonObject mixed = object(body, "mixed");
            JsonArray items = mixed.has("msg_item") && mixed.get("msg_item").isJsonArray()
                    ? mixed.getAsJsonArray("msg_item") : new JsonArray();
            for (JsonElement element : items) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                if (!"text".equals(WeComProtocol.string(item, "msgtype"))) continue;
                String value = WeComProtocol.string(object(item, "text"), "content");
                if (blank(value)) continue;
                if (mixedText.length() > 0) mixedText.append(' ');
                mixedText.append(value.trim());
            }
            content = mixedText.toString();
        } else if ("image".equals(type)) {
            content = "[图片]";
        } else if ("file".equals(type)) {
            content = "[文件]";
        }
        String normalized = content == null ? "" : content.trim().replaceAll("\\s+", " ");
        int maxCodePoints = 24;
        if (normalized.codePointCount(0, normalized.length()) <= maxCodePoints) return normalized;
        int end = normalized.offsetByCodePoints(0, maxCodePoints);
        return normalized.substring(0, end) + "…";
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
        if (route == null) return ChannelSendResult.notAttempted("reply route missing");
        if (blank(route.getRequestId())) {
            return ChannelSendResult.notAttempted("reply request id missing");
        }
        String streamId = WeComProtocol.requestId("stream");
        return sendAndAwait(route.getRequestId(),
                WeComProtocol.respondMarkdown(route.getRequestId(), streamId, markdown),
                "passive");
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
            return ChannelSendResult.notAttempted("channel is not connected");
        }
        boolean attempted = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            CompletableFuture<WeComFrame> future = new CompletableFuture<>();
            pending.put(requestId, future);
            if (!socket.send(payload)) {
                pending.remove(requestId, future);
                continue;
            }
            attempted = true;
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
        return attempted ? ChannelSendResult.failure("ack timeout")
                : ChannelSendResult.notAttempted("websocket rejected send");
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
        inboundExecutor.shutdownNow();
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
