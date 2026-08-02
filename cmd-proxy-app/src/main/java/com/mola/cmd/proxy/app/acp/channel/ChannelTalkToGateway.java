package com.mola.cmd.proxy.app.acp.channel;

import com.mola.cmd.proxy.app.acp.channel.model.ChannelReplyRoute;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelConfig;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelSendResult;
import com.mola.cmd.proxy.app.acp.talkto.ExternalTalkToContactProvider;
import com.mola.cmd.proxy.app.acp.talkto.ExternalTalkToGateway;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToRequest;
import com.mola.cmd.proxy.app.acp.talkto.model.ExternalTalkToContact;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, in-memory, single-use route store and outbound channel gateway. */
public final class ChannelTalkToGateway implements ExternalTalkToGateway, ExternalTalkToContactProvider {
    private static final String PREFIX = "channel:";
    private static final long DEFAULT_TTL_MS = 24L * 60L * 60L * 1000L;
    private static final int DEFAULT_MAX_ROUTES = 10_000;

    private final Map<String, ChannelAdapter> adapters;
    private final Map<String, ChannelConfig> configs;
    private final ConcurrentHashMap<String, RouteEntry> routes = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final long ttlMs;
    private final int maxRoutes;

    public ChannelTalkToGateway(Map<String, ChannelAdapter> adapters) {
        this(adapters, Collections.emptyMap(), DEFAULT_TTL_MS, DEFAULT_MAX_ROUTES);
    }

    ChannelTalkToGateway(Map<String, ChannelAdapter> adapters, long ttlMs, int maxRoutes) {
        this(adapters, Collections.emptyMap(), ttlMs, maxRoutes);
    }

    ChannelTalkToGateway(Map<String, ChannelAdapter> adapters,
                         Map<String, ChannelConfig> configs) {
        this(adapters, configs, DEFAULT_TTL_MS, DEFAULT_MAX_ROUTES);
    }

    ChannelTalkToGateway(Map<String, ChannelAdapter> adapters,
                         Map<String, ChannelConfig> configs,
                         long ttlMs, int maxRoutes) {
        this.adapters = adapters;
        this.configs = configs;
        this.ttlMs = ttlMs;
        this.maxRoutes = maxRoutes;
    }

    public String createRoute(String channelId, ChannelReplyRoute route) {
        cleanupExpired();
        if (routes.size() >= maxRoutes) {
            routes.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().createdAt))
                    .ifPresent(entry -> routes.remove(entry.getKey(), entry.getValue()));
        }
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        String target = PREFIX + channelId + ":r_"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long now = System.currentTimeMillis();
        long routeExpiry = route.getExpiresAt() > 0 ? route.getExpiresAt() : now + ttlMs;
        routes.put(target, new RouteEntry(channelId, route, now,
                Math.min(routeExpiry, now + ttlMs)));
        return target;
    }

    public void discard(String target) {
        routes.remove(target);
    }

    @Override
    public boolean supports(String target) {
        return target != null && target.startsWith(PREFIX);
    }

    @Override
    public String deliver(TalkToRequest request, String senderName, String senderGroupId) {
        String target = request.getTarget();
        RouteEntry entry = routes.get(target);
        if (entry == null) {
            return deliverProactive(request, senderGroupId);
        }
        if (entry.expiresAt <= System.currentTimeMillis()) {
            routes.remove(target, entry);
            return failure("回复路由已过期。");
        }
        if (!entry.claimed.compareAndSet(false, true)) {
            return failure("回复路由正在发送或已消费，不能重复发送。");
        }
        ChannelAdapter adapter = adapters.get(entry.channelId);
        if (adapter == null) {
            entry.claimed.set(false);
            return failure("信道当前不可用。");
        }
        ChannelSendResult result;
        try {
            result = adapter.send(entry.route, request.getContent());
        } catch (RuntimeException e) {
            entry.claimed.set(false);
            return failure("信道发送异常：" + safeError(e.getMessage()));
        }
        if (result != null && result.isSuccess()) {
            routes.remove(target, entry);
            return "[talkTo 结果]\n已通过外部信道发送最终回复。";
        }
        entry.claimed.set(false);
        return failure("信道发送失败：" + safeError(result == null ? null : result.getError()));
    }

    private String deliverProactive(TalkToRequest request, String senderGroupId) {
        String channelId = stableChannelId(request.getTarget());
        if (channelId == null) return failure("回复路由不存在、已过期或已消费。");
        ChannelConfig config = configs.get(channelId);
        if (config == null || !config.isEnabled()) return failure("信道不存在或未启用。");
        if (config.getBinding() == null
                || senderGroupId == null
                || !senderGroupId.equals(config.getBinding().getGroupId())) {
            return failure("当前 ACP 不是该信道绑定的 client。");
        }
        String chatId = trim(config.getDefaultChatId());
        if (chatId.isEmpty()) return failure("信道未配置 defaultChatId，无法主动发送。");
        ChannelAdapter adapter = adapters.get(channelId);
        if (adapter == null) return failure("信道当前不可用。");
        ChannelSendResult result;
        try {
            result = adapter.sendProactive(chatId, request.getContent());
        } catch (RuntimeException e) {
            return failure("信道发送异常：" + safeError(e.getMessage()));
        }
        if (result != null && result.isSuccess()) {
            return "[talkTo 结果]\n已通过外部信道主动发送消息。";
        }
        return failure("信道发送失败：" + safeError(result == null ? null : result.getError()));
    }

    @Override
    public List<ExternalTalkToContact> contactsForGroup(String groupId) {
        if (groupId == null) return Collections.emptyList();
        List<ExternalTalkToContact> result = new ArrayList<>();
        for (ChannelConfig config : configs.values()) {
            if (!config.isEnabled() || config.getBinding() == null
                    || !groupId.equals(config.getBinding().getGroupId())
                    || trim(config.getDefaultChatId()).isEmpty()) continue;
            result.add(new ExternalTalkToContact(
                    PREFIX + config.getId(), config.getId(), "外部信道"));
        }
        result.sort(Comparator.comparing(ExternalTalkToContact::getTarget));
        return Collections.unmodifiableList(result);
    }

    public void clear() { routes.clear(); }
    int routeCount() { cleanupExpired(); return routes.size(); }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        routes.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
    }

    private static String failure(String message) {
        return "[talkTo 结果]\n发送失败：" + message;
    }

    private static String safeError(String error) {
        return error == null || error.trim().isEmpty() ? "unknown error" : error;
    }

    private static String stableChannelId(String target) {
        if (target == null || !target.startsWith(PREFIX)) return null;
        String channelId = target.substring(PREFIX.length());
        return channelId.isEmpty() || channelId.contains(":") ? null : channelId;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class RouteEntry {
        private final String channelId;
        private final ChannelReplyRoute route;
        private final long createdAt;
        private final long expiresAt;
        private final AtomicBoolean claimed = new AtomicBoolean(false);

        private RouteEntry(String channelId, ChannelReplyRoute route,
                           long createdAt, long expiresAt) {
            this.channelId = channelId;
            this.route = route;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }
    }
}
