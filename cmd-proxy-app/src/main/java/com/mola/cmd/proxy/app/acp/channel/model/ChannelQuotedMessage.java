package com.mola.cmd.proxy.app.acp.channel.model;

/** One-level quoted content supplied directly by the external channel callback. */
public final class ChannelQuotedMessage {
    private final String messageType;
    private final String text;

    public ChannelQuotedMessage(String messageType, String text) {
        this.messageType = messageType;
        this.text = text;
    }

    public String getMessageType() { return messageType; }
    public String getText() { return text; }
}
