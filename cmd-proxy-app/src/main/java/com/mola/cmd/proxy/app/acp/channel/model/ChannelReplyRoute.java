package com.mola.cmd.proxy.app.acp.channel.model;

/** Opaque-to-agent addressing data retained only inside cmd-proxy. */
public final class ChannelReplyRoute {
    private final String requestId;
    private final String messageId;
    private final String userId;
    private final String chatId;
    private final String chatType;
    private final long expiresAt;

    public ChannelReplyRoute(String requestId, String messageId, String userId,
                             String chatId, String chatType, long expiresAt) {
        this.requestId = requestId;
        this.messageId = messageId;
        this.userId = userId;
        this.chatId = chatId;
        this.chatType = chatType;
        this.expiresAt = expiresAt;
    }

    public String getRequestId() { return requestId; }
    public String getMessageId() { return messageId; }
    public String getUserId() { return userId; }
    public String getChatId() { return chatId; }
    public String getChatType() { return chatType; }
    public long getExpiresAt() { return expiresAt; }
}
