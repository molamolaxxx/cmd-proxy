package com.mola.cmd.proxy.app.acp.gateway;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Failure-isolated fan-out used only while an ACP turn emits callbacks. */
public final class CompositeAcpResponseListener implements AcpResponseListener {
    private static final Logger logger = LoggerFactory.getLogger(CompositeAcpResponseListener.class);
    private final AcpResponseListener primary;
    private final AcpResponseListener projection;

    public CompositeAcpResponseListener(AcpResponseListener primary,
                                        AcpResponseListener projection) {
        this.primary = primary;
        this.projection = projection;
    }

    @Override public void markNextTermination(String value) {
        call(() -> primary.markNextTermination(value));
        call(() -> projection.markNextTermination(value));
    }
    @Override public void clearNextTermination() {
        call(primary::clearNextTermination); call(projection::clearNextTermination);
    }
    @Override public void onMessage(String text) {
        call(() -> primary.onMessage(text)); call(() -> projection.onMessage(text));
    }
    @Override public void onToolCall(String id, String title, String status, JsonObject update) {
        call(() -> primary.onToolCall(id, title, status, update));
        call(() -> projection.onToolCall(id, title, status, update));
    }
    @Override public void onSubAgentEvent(String type, String name, String detail) {
        call(() -> primary.onSubAgentEvent(type, name, detail));
        call(() -> projection.onSubAgentEvent(type, name, detail));
    }
    @Override public void onScheduleEvent(String type, String detail, boolean expanded) {
        call(() -> primary.onScheduleEvent(type, detail, expanded));
        call(() -> projection.onScheduleEvent(type, detail, expanded));
    }
    @Override public void onTalkToEvent(String type, String robot, String content) {
        call(() -> primary.onTalkToEvent(type, robot, content));
        call(() -> projection.onTalkToEvent(type, robot, content));
    }
    @Override public void onCompactionEvent(String type, String provider) {
        call(() -> primary.onCompactionEvent(type, provider));
        call(() -> projection.onCompactionEvent(type, provider));
    }
    @Override public void onTaskEvent(JsonObject payload) {
        call(() -> primary.onTaskEvent(payload)); call(() -> projection.onTaskEvent(payload));
    }
    @Override public void onLifecycleEvent(String type, String from, String to,
                                           long durationMillis, boolean newSession) {
        call(() -> primary.onLifecycleEvent(type, from, to, durationMillis, newSession));
        call(() -> projection.onLifecycleEvent(type, from, to, durationMillis, newSession));
    }
    @Override public void onComplete(String response) {
        call(() -> primary.onComplete(response)); call(() -> projection.onComplete(response));
    }
    @Override public void onError(Exception error) {
        call(() -> primary.onError(error)); call(() -> projection.onError(error));
    }

    private static void call(Runnable action) {
        try { action.run(); }
        catch (RuntimeException failure) {
            logger.warn("ACP response projection failed; remaining projections continue", failure);
        }
    }
}
