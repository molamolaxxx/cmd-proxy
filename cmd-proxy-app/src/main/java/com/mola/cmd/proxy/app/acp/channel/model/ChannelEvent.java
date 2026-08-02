package com.mola.cmd.proxy.app.acp.channel.model;

import java.util.Objects;

/** Adapter-neutral external text event. Protocol-specific credentials never enter this model. */
public final class ChannelEvent {
    private final String channelId;
    private final String eventId;
    private final String senderId;
    private final String senderDisplayName;
    private final String content;
    private final ChannelReplyRoute replyRoute;

    public ChannelEvent(String channelId, String eventId, String senderId,
                        String senderDisplayName, String content,
                        ChannelReplyRoute replyRoute) {
        this.channelId = requireText(channelId, "channelId");
        this.eventId = requireText(eventId, "eventId");
        this.senderId = requireText(senderId, "senderId");
        this.senderDisplayName = requireText(senderDisplayName, "senderDisplayName");
        this.content = requireText(content, "content");
        this.replyRoute = Objects.requireNonNull(replyRoute, "replyRoute");
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
    public ChannelReplyRoute getReplyRoute() { return replyRoute; }
}
