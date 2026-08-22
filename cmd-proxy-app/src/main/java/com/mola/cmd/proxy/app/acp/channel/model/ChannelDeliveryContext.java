package com.mola.cmd.proxy.app.acp.channel.model;

import java.util.Objects;

/** Durable channel conversation address inherited by scheduled work. */
public final class ChannelDeliveryContext {
    private final String channelId;
    private final String chatType;
    private final String conversationAddress;
    private final String senderId;
    private final String senderDisplayName;

    public ChannelDeliveryContext(String channelId, String chatType,
                                  String conversationAddress, String senderId,
                                  String senderDisplayName) {
        this.channelId = requireText(channelId, "channelId");
        this.chatType = requireText(chatType, "chatType");
        this.conversationAddress = requireText(conversationAddress, "conversationAddress");
        this.senderId = requireText(senderId, "senderId");
        this.senderDisplayName = requireText(senderDisplayName, "senderDisplayName");
    }

    public static ChannelDeliveryContext from(ChannelTurnContext context) {
        if (context == null) return null;
        return new ChannelDeliveryContext(context.getChannelId(), context.getChatType(),
                context.getConversationAddress(), context.getSenderId(),
                context.getSenderDisplayName());
    }

    public String getChannelId() { return channelId; }
    public String getChatType() { return chatType; }
    public String getConversationAddress() { return conversationAddress; }
    public String getSenderId() { return senderId; }
    public String getSenderDisplayName() { return senderDisplayName; }

    private static String requireText(String value, String field) {
        String clean = Objects.requireNonNull(value, field).trim();
        if (clean.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return clean;
    }
}
