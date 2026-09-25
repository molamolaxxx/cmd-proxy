package com.mola.cmd.proxy.app.acp.channel.model;

/** A system-discovered conversation that may be selected for proactive delivery. */
public class ChannelChatTarget {
    private String id;
    private String displayName;
    private String chatType;
    private String lastMessagePreview;
    private long lastSeenAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getChatType() { return chatType; }
    public void setChatType(String chatType) { this.chatType = chatType; }
    public String getLastMessagePreview() { return lastMessagePreview; }
    public void setLastMessagePreview(String lastMessagePreview) {
        this.lastMessagePreview = lastMessagePreview;
    }
    public long getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(long lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
