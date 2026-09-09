package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONObject;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry;
import com.mola.cmd.proxy.app.acp.team.TeamManager;

import java.util.Objects;

/** Projects one task payload through the target client's normal live/history listener. */
public final class ListenerTaskCardProjector implements TaskCardProjector {
    private final AcpClientRegistry mainClients;
    private final TeamManager teams;

    public ListenerTaskCardProjector(AcpClientRegistry mainClients, TeamManager teams) {
        this.mainClients = Objects.requireNonNull(mainClients, "mainClients");
        this.teams = teams;
    }

    @Override
    public void project(JSONObject assignee, JSONObject card) {
        if (assignee == null || card == null) return;
        AcpClient client = client(assignee);
        if (client == null || client.getGlobalListener() == null) return;
        JsonObject payload = JsonParser.parseString(card.toJSONString()).getAsJsonObject();
        if (!client.getClientIdentity().isStarweave()
                && !client.getClientIdentity().isTeam()) {
            client.getHistoryManager().addEventMessage("TASK_EVENT", payload);
        }
        client.getLiveOutputListener().onTaskEvent(payload);
    }

    private AcpClient client(JSONObject assignee) {
        String teamId = trim(assignee.getString("teamId"));
        if (!teamId.isEmpty()) {
            if (teams == null) return null;
            return teams.getClientRegistry().get(
                    teamId, trim(assignee.getString("teamMemberId"))).orElse(null);
        }
        String groupId = trim(assignee.getString("groupId"));
        return groupId.isEmpty() ? null : mainClients.getClient(groupId);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
