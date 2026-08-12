package com.mola.cmd.proxy.app.acp.team.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.team.model.TeamMetricsSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fast Team V1 transport discovery 与 String resultMap 编码。
 */
public final class TeamTransportProtocol {

    public static final String TRANSPORT_PREFIX = "team-acp-";
    public static final String DESCRIBE_COMMAND = "acpTeamDescribe";
    public static final String CREATE_COMMAND = "acpTeamCreate";
    public static final String LIST_COMMAND = "acpTeamList";
    public static final String GET_COMMAND = "acpTeamGet";
    public static final String DELETE_COMMAND = "acpTeamDelete";
    public static final String SEND_COMMAND = "acpTeamSend";
    public static final String CANCEL_COMMAND = "acpTeamCancel";
    public static final String NEW_SESSION_COMMAND = "acpTeamNewSession";
    public static final String LIST_SESSIONS_COMMAND = "acpTeamListSessions";
    public static final String RESTORE_SESSION_COMMAND = "acpTeamRestoreSession";
    public static final String GET_STATUS_COMMAND = "acpTeamGetStatus";
    public static final String GET_CONTEXT_USAGE_COMMAND = "acpTeamGetContextUsage";
    public static final String MEMORY_DREAM_COMMAND = "acpTeamMemoryDream";
    public static final String TALK_TO_DELIVER_COMMAND = "acpTeamTalkToDeliver";
    public static final String EVENT_COMMAND = "acpTeamEvent";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private TeamTransportProtocol() {
    }

    public static Map<String, String> describeResult(
            String requestId, TeamTransportDescriptor descriptor) {
        return describeResult(requestId, descriptor, null);
    }

    public static Map<String, String> describeResult(
            String requestId, TeamTransportDescriptor descriptor,
            TeamMetricsSnapshot metrics) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("schemaVersion", descriptor.getSchemaVersion());
        result.put("requestId", requestId == null ? "" : requestId);
        result.put("accepted", "true");
        result.put("code", "OK");
        result.put("message", "Fast Team transport available");
        JsonObject data = GSON.toJsonTree(descriptor).getAsJsonObject();
        if (metrics != null) {
            data.add("metrics", GSON.toJsonTree(metrics));
        }
        result.put("data", GSON.toJson(data));
        return result;
    }

    public static Map<String, String> discoveryFields(TeamTransportDescriptor descriptor) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("teamSchemaVersion", descriptor.getSchemaVersion());
        fields.put("teamCmdProxyInstanceId", descriptor.getCmdProxyInstanceId());
        fields.put("teamTransportGroup", descriptor.getTransportGroup());
        fields.put("teamDiscovery", GSON.toJson(descriptor));
        return fields;
    }
}
