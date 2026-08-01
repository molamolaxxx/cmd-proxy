package com.mola.cmd.proxy.app.acp.team.event;

import com.mola.cmd.proxy.app.acp.team.protocol.TeamTransportProtocol;
import com.mola.cmd.proxy.client.provider.CmdReceiver;
import com.mola.cmd.proxy.client.resp.CmdResponseContent;

public final class RpcTeamEventSink implements TeamEventSink {

    private final TeamCallbackSender sender;

    public RpcTeamEventSink() {
        this((command, group, response) ->
                CmdReceiver.INSTANCE.callback(command, group, response));
    }

    RpcTeamEventSink(TeamCallbackSender sender) {
        this.sender = java.util.Objects.requireNonNull(sender, "sender");
    }

    @Override
    public void publish(TeamEventEnvelope event) {
        sender.send(TeamTransportProtocol.EVENT_COMMAND, event.getTransportGroup(),
                new CmdResponseContent(event.getEventId(),
                        TeamEventCodec.toResultMap(event)));
    }
}
