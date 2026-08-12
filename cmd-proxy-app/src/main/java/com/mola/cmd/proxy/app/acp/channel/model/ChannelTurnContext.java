package com.mola.cmd.proxy.app.acp.channel.model;

import java.util.Objects;
import java.util.UUID;

/** Immutable routing authority for one channel-originated Agent turn. */
public final class ChannelTurnContext {
    private final String turnId;
    private final String channelId;
    private final String replyTarget;
    private final String chatType;
    private final String conversationAddress;

    public ChannelTurnContext(String channelId, String replyTarget, String chatType,
                              String conversationAddress) {
        this(UUID.randomUUID().toString(), channelId, replyTarget, chatType,
                conversationAddress);
    }

    ChannelTurnContext(String turnId, String channelId, String replyTarget, String chatType,
                       String conversationAddress) {
        this.turnId = requireText(turnId, "turnId");
        this.channelId = requireText(channelId, "channelId");
        this.replyTarget = requireText(replyTarget, "replyTarget");
        this.chatType = requireText(chatType, "chatType");
        this.conversationAddress = requireText(conversationAddress, "conversationAddress");
    }

    public String getTurnId() { return turnId; }
    public String getChannelId() { return channelId; }
    public String getReplyTarget() { return replyTarget; }
    public String getChatType() { return chatType; }
    public String getConversationAddress() { return conversationAddress; }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String clean = value.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return clean;
    }
}
