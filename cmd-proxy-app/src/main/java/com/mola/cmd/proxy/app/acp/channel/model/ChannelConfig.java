package com.mola.cmd.proxy.app.acp.channel.model;

public class ChannelConfig {
    public static final String TYPE_WECOM_WS = "WECOM_WS";

    private String id;
    private String type;
    private boolean enabled;
    /** Whether external messages may enter the bound ACP. Outbound remains available. */
    private volatile boolean inboundEnabled = true;
    private String botId;
    private String secret;
    private String wsUrl = "wss://openws.work.weixin.qq.com";
    /** 主动 talkTo 时固定接收者，可填写企业微信 userid 或群 chatid。 */
    private String defaultChatId;
    private ChannelBinding binding;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isInboundEnabled() { return inboundEnabled; }
    public void setInboundEnabled(boolean inboundEnabled) { this.inboundEnabled = inboundEnabled; }
    public String getBotId() { return botId; }
    public void setBotId(String botId) { this.botId = botId; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public String getWsUrl() { return wsUrl; }
    public void setWsUrl(String wsUrl) { this.wsUrl = wsUrl; }
    public String getDefaultChatId() { return defaultChatId; }
    public void setDefaultChatId(String defaultChatId) { this.defaultChatId = defaultChatId; }
    public ChannelBinding getBinding() { return binding; }
    public void setBinding(ChannelBinding binding) { this.binding = binding; }
}
