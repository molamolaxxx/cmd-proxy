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
    private final ClientResolver clientResolver;
    private final TalkToDispatcher dispatcher;
    private final ChannelTalkToGateway gateway;
    private final ReentrantReadWriteLock inboundGate = new ReentrantReadWriteLock();

    public ChannelTalkToBridge(Map<String, ChannelConfig> configs,
                               AcpClientRegistry clientRegistry,
                               TalkToDispatcher dispatcher,
                               ChannelTalkToGateway gateway) {
        this.configs = configs;
        this.clientResolver = clientRegistry::getClient;
        this.dispatcher = dispatcher;
        this.gateway = gateway;
    }

    ChannelTalkToBridge(Map<String, ChannelConfig> configs, ClientResolver clientResolver,
                        TalkToDispatcher dispatcher, ChannelTalkToGateway gateway) {
        this.configs = configs;
        this.clientResolver = clientResolver;
        this.dispatcher = dispatcher;
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
            String groupId = config.getBinding().getGroupId();
            String replyTarget = gateway.createRoute(event.getChannelId(), event.getReplyRoute());
            AcpClient client = clientResolver.getClient(groupId);
            if (!isMainClient(client)) {
                replyFailureAsync(replyTarget,
                        "信道当前未绑定到可用的 MAIN ACP，请检查配置。", groupId);
                return TalkToDispatcher.InboundDeliveryResult.rejected("ACP binding not found");
            }

            ChannelTalkToMessage message = new ChannelTalkToMessage(
                    replyTarget, config.getId(), event.getSenderDisplayName(), event.getSenderId(),
                    event.getReplyRoute().getChatType(), event.getReplyRoute().getChatId(),
                    event.getContent());
            TalkToDispatcher.InboundDeliveryResult result =
                    dispatcher.deliverInbound(groupId, client, message);
            if (result.getStatus() == TalkToDispatcher.InboundDeliveryResult.Status.REJECTED) {
                replyFailureAsync(replyTarget, "当前处理队列已满，请稍后重试。", groupId);
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

    private void replyFailureAsync(String replyTarget, String content, String groupId) {
        java.util.concurrent.CompletableFuture.runAsync(() -> gateway.deliver(
                new TalkToRequest(replyTarget, content, 0), "cmd-proxy", groupId));
    }

    private static boolean isMainClient(AcpClient client) {
        return client != null && client.getClientIdentity() != null
                && client.getClientIdentity().getScope() == AcpClientIdentity.Scope.MAIN
                && (client.getRobotParam() == null || !client.getRobotParam().isOnlySubAgent());
    }
}
