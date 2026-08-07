package com.mola.cmd.proxy.app.acp.channel.model;

import java.util.Objects;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Adapter-neutral external event. Protocol-specific URLs and credentials never enter this model. */
public final class ChannelEvent {
    private final String channelId;
    private final String eventId;
    private final String senderId;
    private final String senderDisplayName;
    private final String content;
    private final String messageType;
    private final ChannelQuotedMessage quotedMessage;
    private final List<ChannelAttachment> attachments;
    private final ChannelReplyRoute replyRoute;

    public ChannelEvent(String channelId, String eventId, String senderId,
                        String senderDisplayName, String content,
                        ChannelReplyRoute replyRoute) {
        this.channelId = requireText(channelId, "channelId");
        this.eventId = requireText(eventId, "eventId");
        this.senderId = requireText(senderId, "senderId");
        this.senderDisplayName = requireText(senderDisplayName, "senderDisplayName");
        this.content = requireText(content, "content");
        this.messageType = "text";
        this.quotedMessage = null;
        this.attachments = Collections.emptyList();
        this.replyRoute = Objects.requireNonNull(replyRoute, "replyRoute");
    }

    public ChannelEvent(String channelId, String eventId, String senderId,
                        String senderDisplayName, String messageType, String content,
                        ChannelQuotedMessage quotedMessage,
                        List<ChannelAttachment> attachments,
                        ChannelReplyRoute replyRoute) {
        this.channelId = requireText(channelId, "channelId");
        this.eventId = requireText(eventId, "eventId");
        this.senderId = requireText(senderId, "senderId");
        this.senderDisplayName = requireText(senderDisplayName, "senderDisplayName");
        this.messageType = requireText(messageType, "messageType");
        this.content = content == null ? "" : content.trim();
        this.quotedMessage = quotedMessage;
        this.attachments = attachments == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(attachments));
        this.replyRoute = Objects.requireNonNull(replyRoute, "replyRoute");
        if (this.content.isEmpty() && this.attachments.isEmpty()) {
            throw new IllegalArgumentException("content and attachments must not both be empty");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public String getChannelId() { return channelId; }
    public String getEventId() { return eventId; }
    public String getSenderId() { return senderId; }
    public String getSenderDisplayName() { return senderDisplayName; }
    public String getContent() { return content; }
    public String getMessageType() { return messageType; }
    public ChannelQuotedMessage getQuotedMessage() { return quotedMessage; }
    public List<ChannelAttachment> getAttachments() { return attachments; }
    public boolean shouldInvokeAcp() { return !content.isEmpty(); }
    public ChannelReplyRoute getReplyRoute() { return replyRoute; }
}
