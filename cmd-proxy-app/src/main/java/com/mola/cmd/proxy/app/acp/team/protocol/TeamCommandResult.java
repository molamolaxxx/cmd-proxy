package com.mola.cmd.proxy.app.acp.team.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TeamCommandResult {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private String schemaVersion;
    private String requestId;
    private boolean accepted;
    private String code;
    private String message;
    private Long teamVersion;
    private Object data;

    @SuppressWarnings("unused")
    private TeamCommandResult() {
    }

    private TeamCommandResult(String requestId, boolean accepted, String code,
                              String message, Long teamVersion, Object data) {
        this.schemaVersion = TeamDefinition.SCHEMA_VERSION;
        this.requestId = requestId == null ? "" : requestId;
        this.accepted = accepted;
        this.code = code;
        this.message = message;
        this.teamVersion = teamVersion;
        this.data = data;
    }

    public static TeamCommandResult success(String requestId, String code,
                                            String message, Long teamVersion,
                                            Object data) {
        return new TeamCommandResult(requestId, true, code, message, teamVersion, data);
    }

    public static TeamCommandResult error(String requestId, TeamErrorCode code,
                                          String message) {
        return new TeamCommandResult(requestId, false, code.name(), message, null, null);
    }

    public static TeamCommandResult fromSnapshotJson(String json) {
        JsonObject value = JsonParser.parseString(json).getAsJsonObject();
        TeamCommandResult result = new TeamCommandResult(
                value.get("requestId").getAsString(),
                value.get("accepted").getAsBoolean(),
                value.get("code").getAsString(),
                value.get("message").getAsString(),
                value.has("teamVersion") ? value.get("teamVersion").getAsLong() : null,
                value.has("data") ? value.get("data").deepCopy() : null);
        if (value.has("schemaVersion")) {
            result.schemaVersion = value.get("schemaVersion").getAsString();
        }
        return result;
    }

    public Map<String, String> toResultMap() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("schemaVersion", schemaVersion);
        result.put("requestId", requestId);
        result.put("accepted", Boolean.toString(accepted));
        result.put("code", code);
        result.put("message", message);
        if (teamVersion != null) {
            result.put("teamVersion", Long.toString(teamVersion));
        }
        if (data != null) {
            result.put("data", GSON.toJson(data));
        }
        return result;
    }

    public TeamCommandResult withRequestId(String newRequestId) {
        return new TeamCommandResult(newRequestId, accepted, code, message,
                teamVersion, data);
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getRequestId() {
        return requestId;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public Long getTeamVersion() {
        return teamVersion;
    }

    public Object getData() {
        return data;
    }
}
