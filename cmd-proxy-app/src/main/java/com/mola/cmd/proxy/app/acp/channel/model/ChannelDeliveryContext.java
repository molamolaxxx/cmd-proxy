package com.mola.cmd.proxy.app.acp.channel.model;

import java.util.Objects;

/** Durable channel conversation address inherited by scheduled work. */
public final class ChannelDeliveryContext {
    private final String channelId;
    private final String chatType;
    private final String conversationAddress;
    private final String senderId;
    private final String senderDisplayName;
    private final String ownerKey;

    public ChannelDeliveryContext(String channelId, String chatType,
                                  String conversationAddress, String senderId,
                                  String senderDisplayName) {
        this.channelId = requireText(channelId, "channelId");
        this.chatType = requireText(chatType, "chatType");
        this.conversationAddress = requireText(conversationAddress, "conversationAddress");
        this.senderId = requireText(senderId, "senderId");
        this.senderDisplayName = requireText(senderDisplayName, "senderDisplayName");
        this.ownerKey = null;
    }

    public ChannelDeliveryContext(String channelId, String chatType,
                                  String conversationAddress, String senderId,
                                  String senderDisplayName, String ownerKey) {
        this.channelId = requireText(channelId, "channelId");
        this.chatType = requireText(chatType, "chatType");
        this.conversationAddress = requireText(conversationAddress, "conversationAddress");
        this.senderId = requireText(senderId, "senderId");
        this.senderDisplayName = requireText(senderDisplayName, "senderDisplayName");
        this.ownerKey = optionalText(ownerKey);
    }

    public static ChannelDeliveryContext from(ChannelTurnContext context) {
        if (context == null) return null;
        return new ChannelDeliveryContext(context.getChannelId(), context.getChatType(),
                context.getConversationAddress(), context.getSenderId(),
                context.getSenderDisplayName(), context.getOwnerKey());
    }

    public String getChannelId() { return channelId; }
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
        String clean = Objects.requireNonNull(value, field).trim();
        if (clean.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return clean;
    }
}
