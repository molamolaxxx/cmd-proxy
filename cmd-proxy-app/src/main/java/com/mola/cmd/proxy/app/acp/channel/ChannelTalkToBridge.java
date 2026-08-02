package com.mola.cmd.proxy.app.acp.channel;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelConfig;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelEvent;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToRequest;

import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Resolves the current bound MAIN client for every event and enters the shared TalkTo inbox. */
public final class ChannelTalkToBridge {
    public interface ClientResolver {
        AcpClient getClient(String groupId);
    }

    private final Map<String, ChannelConfig> configs;
    private final ChannelBindingResolver bindingResolver;
    private final ChannelTalkToGateway gateway;
    private final ReentrantReadWriteLock inboundGate = new ReentrantReadWriteLock();

    public ChannelTalkToBridge(Map<String, ChannelConfig> configs,
                               AcpClientRegistry clientRegistry,
                               TalkToDispatcher dispatcher,
                               ChannelTalkToGateway gateway) {
        this.configs = configs;
        this.bindingResolver = new DefaultChannelBindingResolver(
                clientRegistry, dispatcher, null);
        this.gateway = gateway;
    }

    public ChannelTalkToBridge(Map<String, ChannelConfig> configs,
                               ChannelBindingResolver bindingResolver,
                               ChannelTalkToGateway gateway) {
        this.configs = configs;
        this.bindingResolver = bindingResolver;
        this.gateway = gateway;
    }

    ChannelTalkToBridge(Map<String, ChannelConfig> configs, ClientResolver clientResolver,
                        TalkToDispatcher dispatcher, ChannelTalkToGateway gateway) {
        this.configs = configs;
        this.bindingResolver = binding -> {
            AcpClient client = clientResolver.getClient(binding.getGroupId());
            return client == null ? null : new ChannelBoundTarget(
                    client, dispatcher, binding.getGroupId(), binding.getGroupId());
        };
        this.gateway = gateway;
    }

    public TalkToDispatcher.InboundDeliveryResult onEvent(ChannelEvent event) {
        inboundGate.readLock().lock();
        try {
            ChannelConfig config = configs.get(event.getChannelId());
            if (config == null || config.getBinding() == null) {
                return TalkToDispatcher.InboundDeliveryResult.rejected("channel binding not found");
            }
            if (!config.isInboundEnabled()) {
                return TalkToDispatcher.InboundDeliveryResult.rejected("channel inbound disabled");
            }
            String ownerKey = ownerKey(config.getBinding());
            String replyTarget = gateway.createRoute(
                    event.getChannelId(), event.getReplyRoute(), ownerKey);
            ChannelBoundTarget target = bindingResolver.resolve(config.getBinding());
            AcpClient client = target == null ? null : target.getClient();
            if (!isMainClient(client)) {
                replyFailureAsync(replyTarget, "信道当前未绑定到可用的 ACP，请检查配置。");
                return TalkToDispatcher.InboundDeliveryResult.rejected("ACP binding not found");
            }

            ChannelTalkToMessage message = new ChannelTalkToMessage(
                    replyTarget, config.getId(), event.getSenderDisplayName(), event.getSenderId(),
                    event.getReplyRoute().getChatType(), event.getReplyRoute().getChatId(),
                    event.getContent());
            TalkToDispatcher.InboundDeliveryResult result =
                    target.getDispatcher().deliverInbound(
                            target.getRoutingKey(), client, message);
            if (result.getStatus() == TalkToDispatcher.InboundDeliveryResult.Status.REJECTED) {
                replyFailureAsync(replyTarget, "当前处理队列已满，请稍后重试。");
            }
            return result;
        } finally {
            inboundGate.readLock().unlock();
        }
    }

    /**
     * Changes the gate under a write lock. Once this method returns after disabling,
     * no event can still be between the gate check and ACP delivery.
     *
     * @return the previous value
     */
    public boolean setInboundEnabled(String channelId, boolean enabled) {
        inboundGate.writeLock().lock();
        try {
            ChannelConfig config = configs.get(channelId);
            if (config == null) {
                throw new IllegalArgumentException("channel not found: " + channelId);
            }
            boolean previous = config.isInboundEnabled();
            config.setInboundEnabled(enabled);
            return previous;
        } finally {
            inboundGate.writeLock().unlock();
        }
    }

    private void replyFailureAsync(String replyTarget, String content) {
        java.util.concurrent.CompletableFuture.runAsync(() -> gateway.deliverSystem(
                new TalkToRequest(replyTarget, content, 0)));
    }

    private static boolean isMainClient(AcpClient client) {
        return client != null && client.getClientIdentity() != null
                && (client.getClientIdentity().getScope() == AcpClientIdentity.Scope.MAIN
                    || client.getClientIdentity().getScope() == AcpClientIdentity.Scope.TEAM)
                && (client.getRobotParam() == null || !client.getRobotParam().isOnlySubAgent());
    }

    private static String ownerKey(com.mola.cmd.proxy.app.acp.channel.model.ChannelBinding binding) {
        if (com.mola.cmd.proxy.app.acp.channel.model.ChannelBinding.TYPE_TEAM_MEMBER
                .equals(binding.getType())) {
            return "team:" + binding.getTeamId().trim() + ":" + binding.getTeamMemberId().trim();
        }
        return binding.getGroupId().trim();
    }
}
