package com.mola.cmd.proxy.app.acp.channel.model;

import java.util.ArrayList;
import java.util.List;

public class ChannelConfig {
    public static final String TYPE_WECOM_WS = "WECOM_WS";
    public static final String USER_BEHAVIOR_QUEUE = "QUEUE";
    public static final String USER_BEHAVIOR_INTERRUPT = "INTERRUPT";

    private String id;
    private String type;
    private boolean enabled;
    /** Whether external messages may enter the bound ACP. Outbound remains available. */
    private volatile boolean inboundEnabled = true;
    /** Whether direct messages may enter the bound ACP. Group messages remain available. */
    private volatile boolean privateChatEnabled = true;
    /** How a new inbound user message behaves while the bound ACP is busy. */
    private volatile String userBehavior = USER_BEHAVIOR_QUEUE;
    private String botId;
    private String secret;
    private String wsUrl = "wss://openws.work.weixin.qq.com";
    /** Explicit proactive target for schedules and other non-channel-originated turns. */
    private String defaultChatId;
    /** System-owned options discovered from inbound conversations. */
    private List<ChannelChatTarget> knownChatTargets = new ArrayList<>();
    private ChannelBinding binding;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isInboundEnabled() { return inboundEnabled; }
    public void setInboundEnabled(boolean inboundEnabled) { this.inboundEnabled = inboundEnabled; }
    public boolean isPrivateChatEnabled() { return privateChatEnabled; }
    public void setPrivateChatEnabled(boolean privateChatEnabled) {
        this.privateChatEnabled = privateChatEnabled;
    }
    public String getUserBehavior() { return userBehavior; }
    public void setUserBehavior(String userBehavior) {
        this.userBehavior = USER_BEHAVIOR_INTERRUPT.equals(userBehavior)
                ? USER_BEHAVIOR_INTERRUPT : USER_BEHAVIOR_QUEUE;
    }
    public String getBotId() { return botId; }
    public void setBotId(String botId) { this.botId = botId; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public String getWsUrl() { return wsUrl; }
    public void setWsUrl(String wsUrl) { this.wsUrl = wsUrl; }
    public String getDefaultChatId() { return defaultChatId; }
    public void setDefaultChatId(String defaultChatId) { this.defaultChatId = defaultChatId; }
    public List<ChannelChatTarget> getKnownChatTargets() { return knownChatTargets; }
    public void setKnownChatTargets(List<ChannelChatTarget> knownChatTargets) {
        this.knownChatTargets = knownChatTargets == null ? new ArrayList<>() : knownChatTargets;
    }
    public ChannelBinding getBinding() { return binding; }
    public void setBinding(ChannelBinding binding) { this.binding = binding; }
}
