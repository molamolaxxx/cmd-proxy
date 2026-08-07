package com.mola.cmd.proxy.app.acp.channel;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelBinding;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelConfig;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelStatus;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import com.mola.cmd.proxy.app.acp.team.TeamManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Instance-scoped channel lifecycle owner. One invalid channel does not stop ACP clients. */
public final class ChannelManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ChannelManager.class);

    public interface AdapterFactory {
        ChannelAdapter create(ChannelConfig config, ChannelTalkToBridge bridge);
    }

    private final List<ChannelConfig> configuredChannels;
    private final String instanceId;
    private final AcpClientRegistry clientRegistry;
    private final TalkToDispatcher dispatcher;
    private final AdapterFactory adapterFactory;
    private final Map<String, ChannelConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, ChannelAdapter> adapters = new ConcurrentHashMap<>();
    private final Map<String, ChannelStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    private final ChannelTalkToGateway gateway;
    private final ChannelTalkToBridge bridge;
    private final TeamManager teamManager;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public ChannelManager(List<ChannelConfig> configuredChannels, String instanceId,
                          AcpClientRegistry clientRegistry, TalkToDispatcher dispatcher,
                          TeamManager teamManager, AdapterFactory adapterFactory) {
        this.configuredChannels = configuredChannels == null
                ? Collections.emptyList() : configuredChannels;
        this.instanceId = instanceId;
        this.clientRegistry = clientRegistry;
        this.dispatcher = dispatcher;
        this.adapterFactory = adapterFactory;
        this.teamManager = teamManager;
        this.gateway = new ChannelTalkToGateway(adapters, configs);
        this.bridge = new ChannelTalkToBridge(configs,
                new DefaultChannelBindingResolver(clientRegistry, dispatcher, teamManager), gateway);
    }

    public ChannelManager(List<ChannelConfig> configuredChannels, String instanceId,
                          AcpClientRegistry clientRegistry, TalkToDispatcher dispatcher,
                          AdapterFactory adapterFactory) {
        this(configuredChannels, instanceId, clientRegistry, dispatcher, null, adapterFactory);
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        // Adapter.start() may synchronously receive a callback, so expose outbound routing first.
        dispatcher.registerExternalGateway(gateway);
        if (teamManager != null) teamManager.registerExternalGateway(gateway);
        Set<String> ids = new HashSet<>();
        Set<String> botIds = new HashSet<>();
        for (ChannelConfig config : configuredChannels) {
            String error = validate(config, ids, botIds);
            String channelId = config == null ? "<null>" : trim(config.getId());
            if (error != null) {
                if (!channelId.isEmpty()) {
                    statuses.put(channelId, ChannelStatus.ERROR);
                    errors.put(channelId, error);
                }
                logger.error("channel config rejected: channelId={}, errorCode=CHANNEL_CONFIG_INVALID, reason={}",
                        channelId, error);
                continue;
            }
            if (!config.isEnabled()) {
                configs.put(config.getId(), config);
                statuses.put(config.getId(), ChannelStatus.STOPPED);
                continue;
            }
            configs.put(config.getId(), config);
            ChannelAdapter adapter = null;
            try {
                adapter = adapterFactory.create(config, bridge);
                if (adapter == null) throw new IllegalStateException("adapter factory returned null");
                adapters.put(config.getId(), adapter);
                statuses.put(config.getId(), ChannelStatus.CONNECTING);
                adapter.start();
                statuses.put(config.getId(), adapter.getStatus());
            } catch (RuntimeException e) {
                adapters.remove(config.getId());
                if (adapter != null) {
                    try { adapter.stop(); } catch (RuntimeException ignored) { }
                }
                statuses.put(config.getId(), ChannelStatus.ERROR);
                String redacted = redact(e.getMessage(), config.getSecret());
                errors.put(config.getId(), redacted);
                logger.error("channel start failed: channelId={}, errorCode=CHANNEL_CONFIG_INVALID, reason={}",
                        config.getId(), redacted);
            }
        }
    }

    /**
     * Replaces one configured channel without disturbing other channel connections.
     * {@code previousChannelId} may differ from the new id when a channel is renamed.
     * A {@code null} config removes the previous channel.
     */
    public synchronized void reloadChannel(String previousChannelId, ChannelConfig config) {
        if (!started.get()) throw new IllegalStateException("channel manager is not running");
        String previousId = trim(previousChannelId);
        String newId = config == null ? "" : trim(config.getId());
        if (previousId.isEmpty() && newId.isEmpty()) {
            throw new IllegalArgumentException("channel id is required");
        }

        if (config != null) {
            Set<String> ids = new HashSet<>();
            Set<String> botIds = new HashSet<>();
            for (ChannelConfig existing : configs.values()) {
                String existingId = trim(existing.getId());
                if (existingId.equals(previousId)) continue;
                ids.add(existingId);
                if (existing.isEnabled()) botIds.add(trim(existing.getBotId()));
            }
            String error = validate(config, ids, botIds);
            if (error != null) throw new IllegalArgumentException(error);
        }

        if (!previousId.isEmpty()) removeChannel(previousId);
        if (!newId.isEmpty() && !newId.equals(previousId)) removeChannel(newId);
        if (config == null) {
            logger.info("channel removed: channelId={}", previousId);
            return;
        }

        configs.put(config.getId(), config);
        errors.remove(config.getId());
        if (!config.isEnabled()) {
            statuses.put(config.getId(), ChannelStatus.STOPPED);
            logger.info("channel reloaded: channelId={}, status=STOPPED", config.getId());
            return;
        }

        ChannelAdapter adapter = null;
        try {
            adapter = adapterFactory.create(config, bridge);
            if (adapter == null) throw new IllegalStateException("adapter factory returned null");
            adapters.put(config.getId(), adapter);
            statuses.put(config.getId(), ChannelStatus.CONNECTING);
            adapter.start();
            statuses.put(config.getId(), adapter.getStatus());
            logger.info("channel reloaded: channelId={}, status={}",
                    config.getId(), adapter.getStatus());
        } catch (RuntimeException e) {
            adapters.remove(config.getId(), adapter);
            if (adapter != null) {
                try { adapter.stop(); } catch (RuntimeException ignored) { }
            }
            statuses.put(config.getId(), ChannelStatus.ERROR);
            String redacted = redact(e.getMessage(), config.getSecret());
            errors.put(config.getId(), redacted);
            throw new IllegalStateException(redacted, e);
        }
    }

    private void removeChannel(String channelId) {
        ChannelAdapter old = adapters.remove(channelId);
        if (old != null) {
            try { old.stop(); } catch (RuntimeException e) {
                logger.warn("channel stop failed during reload: channelId={}", channelId, e);
            }
        }
        configs.remove(channelId);
        statuses.remove(channelId);
        errors.remove(channelId);
    }

    private String validate(ChannelConfig config, Set<String> ids, Set<String> botIds) {
        if (config == null) return "channel is null";
        String id = trim(config.getId());
        if (id.isEmpty()) return "channel.id is required";
        config.setId(id);
        if (!ids.add(id)) return "duplicate channel.id";
        if (!ChannelConfig.TYPE_WECOM_WS.equals(config.getType())) return "unsupported channel.type";
        if (!config.isEnabled()) return null;
        if (trim(config.getBotId()).isEmpty()) return "botId is required";
        if (!botIds.add(config.getBotId())) return "duplicate enabled botId";
        if (trim(config.getSecret()).isEmpty()) return "secret is required";
        ChannelBinding binding = config.getBinding();
        if (binding == null) return "binding is required";
        if (!instanceId.equals(trim(binding.getInstanceId()))) return "binding.instanceId mismatch";
        if (ChannelBinding.TYPE_MAIN.equals(binding.getType())) {
            if (!trim(binding.getTeamId()).isEmpty() || !trim(binding.getTeamMemberId()).isEmpty()) {
                return "MAIN binding cannot contain team fields";
            }
            String groupId = trim(binding.getGroupId());
            if (groupId.isEmpty()) return "binding.groupId is required";
            AcpClient client = clientRegistry.getClient(groupId);
            if (client == null) return "binding group client not found";
            if (client.getClientIdentity().getScope() != AcpClientIdentity.Scope.MAIN) {
                return "binding client must be MAIN";
            }
            if (client.getRobotParam() != null && client.getRobotParam().isOnlySubAgent()) {
                return "binding client cannot be onlySubAgent";
            }
        } else if (ChannelBinding.TYPE_TEAM_MEMBER.equals(binding.getType())) {
            if (!trim(binding.getGroupId()).isEmpty()) {
                return "TEAM_MEMBER binding cannot contain groupId";
            }
            if (trim(binding.getTeamId()).isEmpty()) return "binding.teamId is required";
            if (trim(binding.getTeamMemberId()).isEmpty()) return "binding.teamMemberId is required";
        } else {
            return "unsupported binding.type";
        }
        return null;
    }

    public ChannelTalkToBridge getBridge() { return bridge; }
    public ChannelTalkToGateway getGateway() { return gateway; }

    public boolean setInboundEnabled(String channelId, boolean enabled) {
        return bridge.setInboundEnabled(channelId, enabled);
    }

    public Map<String, ChannelStatus> getStatuses() {
        Map<String, ChannelStatus> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, ChannelStatus> entry : statuses.entrySet()) {
            ChannelAdapter adapter = adapters.get(entry.getKey());
            snapshot.put(entry.getKey(), adapter == null ? entry.getValue() : adapter.getStatus());
        }
        return snapshot;
    }

    public Map<String, String> getErrors() { return new LinkedHashMap<>(errors); }

    @Override
    public void close() {
        if (!started.compareAndSet(true, false)) return;
        dispatcher.unregisterExternalGateway(gateway);
        if (teamManager != null) teamManager.unregisterExternalGateway(gateway);
        for (ChannelAdapter adapter : adapters.values()) {
            try { adapter.stop(); } catch (RuntimeException e) {
                logger.warn("channel stop failed: channelId={}", adapter.getChannelId(), e);
            }
        }
        adapters.clear();
        gateway.clear();
        for (String id : configs.keySet()) statuses.put(id, ChannelStatus.STOPPED);
        configs.clear();
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unknown error" : value;
    }

    private static String redact(String value, String secret) {
        String result = safe(value);
        return secret == null || secret.isEmpty() ? result : result.replace(secret, "***");
    }
}
