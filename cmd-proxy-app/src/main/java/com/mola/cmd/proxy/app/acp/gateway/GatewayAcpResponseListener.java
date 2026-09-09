package com.mola.cmd.proxy.app.acp.gateway;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;

import java.util.concurrent.atomic.AtomicReference;

/** Structured gateway projection paired with, but never replacing, the owning surface listener. */
public final class GatewayAcpResponseListener implements AcpResponseListener {
    private final AcpClient client;
    private final AtomicReference<String> termination = new AtomicReference<>();

    public GatewayAcpResponseListener(AcpClient client) {
        this.client = client;
    }

    @Override public void markNextTermination(String value) { termination.set(value); }
    @Override public void clearNextTermination() { termination.set(null); }

    @Override public void onMessage(String text) {
        JSONObject payload = object("text", text == null ? "" : text);
        payload.put("format", "markdown");
        publish("assistant.message.delta", null, payload, payload);
    }

    @Override public void onToolCall(String toolCallId, String title, String status,
                                     JsonObject update) {
        JSONObject payload = update == null ? new JSONObject(true)
                : JSON.parseObject(update.toString());
        payload.put("toolCallId", toolCallId);
        payload.put("title", title);
        payload.put("status", status);
        publish("tool_call.updated", toolCallId, payload, payload);
    }

    @Override public void onSubAgentEvent(String eventType, String agentName, String detail) {
        JSONObject payload = object("eventType", eventType);
        payload.put("agentName", agentName);
        payload.put("detail", detail);
        publish("sub_agent.updated", agentName == null ? null : "subagent-" + agentName,
                payload, payload);
    }

    @Override public void onScheduleEvent(String eventType, String detail, boolean expanded) {
        JSONObject payload = object("eventType", eventType);
        payload.put("detail", detail);
        payload.put("expanded", expanded);
        publish("schedule.updated", null, payload, payload);
    }

    @Override public void onTalkToEvent(String eventType, String robotName, String content) {
        JSONObject payload = object("eventType", eventType);
        payload.put("robotName", robotName);
        payload.put("content", content);
        publish("talk_to.updated", null, payload, payload);
    }

    @Override public void onCompactionEvent(String eventType, String provider) {
        JSONObject payload = object("eventType", eventType);
        payload.put("provider", provider);
        publish("compaction.completed", null, payload, payload);
    }

    @Override public void onTaskEvent(JsonObject value) {
        JSONObject payload = value == null ? new JSONObject(true)
                : JSON.parseObject(value.toString());
        String taskId = payload.getString("taskId");
        publish("task.updated", taskId == null ? null : "task-" + taskId, payload, payload);
    }

    @Override public void onComplete(String fullResponse) {
        String marker = termination.getAndSet(null);
        JSONObject payload = object("finishReason",
                marker == null ? "completed" : "cancelled");
        if (marker != null) payload.put("termination", marker);
        publish(marker == null ? "turn.completed" : "turn.cancelled", null, payload, payload);
    }

    @Override public void onError(Exception error) {
        JSONObject payload = object("code", "PROVIDER_ERROR");
        payload.put("message", error == null ? "unknown error" : error.getMessage());
        payload.put("retryable", true);
        publish("turn.error", null, payload, payload);
    }

    private void publish(String type, String cardId, JSONObject payload, JSONObject nativePayload) {
        // Team callbacks are projected once from TeamEventEnvelope so local and remote
        // members share identical ordering and do not produce duplicate cards.
        if (client.getClientIdentity().isTeam()) return;
        GatewayEventBridge.publish(client, type, cardId, payload, nativePayload);
    }

    private static JSONObject object(String key, Object value) {
        JSONObject result = new JSONObject(true);
        result.put(key, value);
        return result;
    }
}
