package com.mola.cmd.proxy.app.acp.channel.model;

/** Explicit inbound conversation assignment, scoped to its channel and Team. */
public class ChannelConversationMapping {
    private String chatType;
    private String conversationId;
    private String teamMemberId;

    public String getChatType() { return chatType; }
    public void setChatType(String chatType) { this.chatType = chatType; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getTeamMemberId() { return teamMemberId; }
    public void setTeamMemberId(String teamMemberId) { this.teamMemberId = teamMemberId; }
}
