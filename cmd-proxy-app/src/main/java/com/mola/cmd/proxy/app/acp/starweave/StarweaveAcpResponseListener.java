package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSON;
import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;

import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Emits structured Starweave events and never calls a MolaChat callback. */
public final class StarweaveAcpResponseListener implements AcpResponseListener {
    private final String groupId;
    private final Supplier<String> sessionIdSupplier;
    private final LongSupplier generationSupplier;
    private final StarweaveSessionEventStore store;
    private final StarweaveTurnTracker turns;

    public StarweaveAcpResponseListener(String groupId,
                                        Supplier<String> sessionIdSupplier,
                                        LongSupplier generationSupplier,
                                        StarweaveSessionEventStore store) {
        this(groupId, sessionIdSupplier, generationSupplier, store,
                new StarweaveTurnTracker());
    }

    StarweaveAcpResponseListener(String groupId,
                                 Supplier<String> sessionIdSupplier,
                                 LongSupplier generationSupplier,
                                 StarweaveSessionEventStore store,
                                 StarweaveTurnTracker turns) {
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        this.sessionIdSupplier = Objects.requireNonNull(sessionIdSupplier, "sessionIdSupplier");
        this.generationSupplier = Objects.requireNonNull(generationSupplier, "generationSupplier");
        this.store = Objects.requireNonNull(store, "store");
        this.turns = Objects.requireNonNull(turns, "turns");
    }

    @Override
    public void onMessage(String text) {
        emit("ASSISTANT_MESSAGE_DELTA", payload("text", text));
    }

    @Override
    public void onToolCall(String toolCallId, String title, String status, JsonObject update) {
        JSONObject payload = new JSONObject(true);
        payload.put("toolCallId", toolCallId);
        payload.put("title", title);
        payload.put("status", status);
        payload.put("update", update == null ? null : JSON.parse(update.toString()));
        emit("TOOL_CALL_UPDATED", payload);
    }

    @Override
    public void onSubAgentEvent(String eventType, String agentName, String detail) {
        JSONObject payload = payload("eventType", eventType);
        payload.put("agentName", agentName);
        payload.put("detail", detail);
        emit("SUB_AGENT_EVENT", payload);
    }

    @Override
    public void onScheduleEvent(String eventType, String detail, boolean expanded) {
        JSONObject payload = payload("eventType", eventType);
        payload.put("detail", detail);
        payload.put("expanded", expanded);
        emit("SCHEDULE_EVENT", payload);
    }

    @Override
    public void onTalkToEvent(String eventType, String robotName, String content) {
        JSONObject payload = payload("eventType", eventType);
        payload.put("robotName", robotName);
        payload.put("content", content);
        emit("TALK_TO_EVENT", payload);
    }

    @Override
    public void onCompactionEvent(String eventType, String provider) {
        JSONObject payload = payload("eventType", eventType);
        payload.put("provider", provider);
        emit("COMPACTION_COMPLETED", payload);
    }

    @Override
    public void onComplete(String fullResponse) {
        emitTerminal("TURN_COMPLETED", new JSONObject(true));
    }

    @Override
    public void onError(Exception error) {
        emitTerminal("TURN_ERROR", payload("message",
                error == null ? "unknown error" : error.getMessage()));
    }

    private void emit(String type, JSONObject payload) {
        store.append(groupId, sessionIdSupplier.get(),
                turns.currentOrBegin(), generationSupplier.getAsLong(), type, payload);
    }

    private void emitTerminal(String type, JSONObject payload) {
        store.append(groupId, sessionIdSupplier.get(),
                turns.complete(), generationSupplier.getAsLong(), type, payload);
    }

    private static JSONObject payload(String key, Object value) {
        JSONObject payload = new JSONObject(true);
        payload.put(key, value);
        return payload;
    }
}
