package com.mola.cmd.proxy.app.acp.channel.model;

/** User-configured proactive destination exposed to the bound Agent as a stable target. */
public class ChannelOutboundTarget {
    private String id;
    private String chatId;
    private String description;

    public ChannelOutboundTarget() { }

    public ChannelOutboundTarget(String id, String chatId, String description) {
        this.id = id;
        this.chatId = chatId;
        this.description = description;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    /** Stable route ids may use Unicode letters/numbers (including Chinese), '_' and '-'. */
    public static boolean isValidId(String id) {
        return id != null && id.matches("[\\p{L}\\p{N}_-]{1,120}");
    }
}
