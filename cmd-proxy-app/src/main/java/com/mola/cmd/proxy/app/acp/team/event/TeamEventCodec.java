package com.mola.cmd.proxy.app.acp.team.event;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TeamEventCodec {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private TeamEventCodec() {
    }

    public static Map<String, String> toResultMap(TeamEventEnvelope event) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("schemaVersion", event.getSchemaVersion());
        result.put("eventId", event.getEventId());
        result.put("eventSeq", Long.toString(event.getEventSeq()));
        result.put("transportGroup", event.getTransportGroup());
        result.put("teamId", event.getTeamId());
        if (event.getTeamMemberId() != null) {
            result.put("teamMemberId", event.getTeamMemberId());
        }
        if (event.getAcpClientId() != null) {
            result.put("acpClientId", event.getAcpClientId());
        }
        result.put("type", event.getType().name());
        result.put("teamVersion", Long.toString(event.getTeamVersion()));
        result.put("timestamp", Long.toString(event.getTimestamp()));
        result.put("data", GSON.toJson(event.getData()));
        return result;
    }
}
