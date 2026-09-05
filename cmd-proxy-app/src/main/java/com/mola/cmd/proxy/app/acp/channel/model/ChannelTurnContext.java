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
    private final String senderId;
    private final String senderDisplayName;
    private final String ownerKey;

    public ChannelTurnContext(String channelId, String replyTarget, String chatType,
                              String conversationAddress) {
        this(UUID.randomUUID().toString(), channelId, replyTarget, chatType,
                conversationAddress, conversationAddress, conversationAddress, null);
    }

    public ChannelTurnContext(String channelId, String replyTarget, String chatType,
                              String conversationAddress, String senderId,
                              String senderDisplayName) {
        this(UUID.randomUUID().toString(), channelId, replyTarget, chatType,
                conversationAddress, senderId, senderDisplayName, null);
    }

    public static ChannelTurnContext owned(String channelId, String replyTarget,
                                           String chatType, String conversationAddress,
                                           String senderId, String senderDisplayName,
                                           String ownerKey) {
        return new ChannelTurnContext(UUID.randomUUID().toString(), channelId,
                replyTarget, chatType, conversationAddress, senderId,
                senderDisplayName, ownerKey);
    }

    ChannelTurnContext(String turnId, String channelId, String replyTarget, String chatType,
                       String conversationAddress) {
        this(turnId, channelId, replyTarget, chatType, conversationAddress,
                conversationAddress, conversationAddress, null);
    }

    ChannelTurnContext(String turnId, String channelId, String replyTarget, String chatType,
                       String conversationAddress, String senderId, String senderDisplayName) {
        this(turnId, channelId, replyTarget, chatType, conversationAddress,
                senderId, senderDisplayName, null);
    }

    ChannelTurnContext(String turnId, String channelId, String replyTarget, String chatType,
                       String conversationAddress, String senderId, String senderDisplayName,
                       String ownerKey) {
        this.turnId = requireText(turnId, "turnId");
        this.channelId = requireText(channelId, "channelId");
        this.replyTarget = requireText(replyTarget, "replyTarget");
        this.chatType = requireText(chatType, "chatType");
        this.conversationAddress = requireText(conversationAddress, "conversationAddress");
        this.senderId = requireText(senderId, "senderId");
        this.senderDisplayName = requireText(senderDisplayName, "senderDisplayName");
        this.ownerKey = optionalText(ownerKey);
    }

    public String getTurnId() { return turnId; }
    public String getChannelId() { return channelId; }
    public String getReplyTarget() { return replyTarget; }
    public String getChatType() { return chatType; }
    public String getConversationAddress() { return conversationAddress; }
    public String getSenderId() { return senderId; }
    public String getSenderDisplayName() { return senderDisplayName; }
    public String getOwnerKey() { return ownerKey; }

    private static String optionalText(String value) {
        if (value == null) return null;
        String clean = value.trim();
        return clean.isEmpty() ? null : clean;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String clean = value.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return clean;
    }
}
