package com.mola.cmd.proxy.app.acp.channel.model;

/** A system-discovered conversation that may be selected for proactive delivery. */
public class ChannelChatTarget {
    private String id;
    private String displayName;
    private String chatType;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getChatType() { return chatType; }
    public void setChatType(String chatType) { this.chatType = chatType; }
}
