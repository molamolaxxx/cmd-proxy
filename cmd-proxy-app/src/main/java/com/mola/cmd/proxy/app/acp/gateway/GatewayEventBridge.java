package com.mola.cmd.proxy.app.acp.gateway;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;

/** Process-local event bridge kept independent from any external transport connection. */
public final class GatewayEventBridge {
    private static volatile AgentGatewayManager manager;

    private GatewayEventBridge() { }

    public static void install(AgentGatewayManager value) { manager = value; }
    public static void clear(AgentGatewayManager expected) {
        if (manager == expected) manager = null;
    }

    public static void publish(AcpClient client, String type, String cardId,
                               JSONObject payload, JSONObject nativePayload) {
        AgentGatewayManager current = manager;
        if (current != null && client != null) {
            current.publish(client, type, cardId, payload, nativePayload);
        }
    }

    public static void publishTeam(TeamEventEnvelope event) {
        AgentGatewayManager current = manager;
        if (current != null && event != null) current.publishTeam(event);
    }
}
