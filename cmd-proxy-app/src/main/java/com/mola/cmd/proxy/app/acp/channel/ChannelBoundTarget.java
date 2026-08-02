package com.mola.cmd.proxy.app.acp.channel;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;

/** A freshly resolved channel destination. Client references are never cached by the channel. */
public final class ChannelBoundTarget {
    private final AcpClient client;
    private final TalkToDispatcher dispatcher;
    private final String routingKey;
    private final String senderKey;

    public ChannelBoundTarget(AcpClient client, TalkToDispatcher dispatcher,
                              String routingKey, String senderKey) {
        this.client = client;
        this.dispatcher = dispatcher;
        this.routingKey = routingKey;
        this.senderKey = senderKey;
    }

    public AcpClient getClient() { return client; }
    public TalkToDispatcher getDispatcher() { return dispatcher; }
    public String getRoutingKey() { return routingKey; }
    public String getSenderKey() { return senderKey; }
}
