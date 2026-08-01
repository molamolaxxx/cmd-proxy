package com.mola.cmd.proxy.app.acp.acpclient.listener;

import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 仅在所属 client lifecycle generation 仍有效时转发回调。
 * 关闭或替换 client 后，旧 turn 的迟到事件会被静默丢弃。
 */
public final class LifecycleGuardedAcpResponseListener implements AcpResponseListener {

    private final AcpResponseListener delegate;
    private final BooleanSupplier active;

    public LifecycleGuardedAcpResponseListener(AcpResponseListener delegate,
                                               BooleanSupplier active) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.active = Objects.requireNonNull(active, "active");
    }

    @Override
    public void onMessage(String text) {
        if (active.getAsBoolean()) delegate.onMessage(text);
    }

    @Override
    public void onToolCall(String toolCallId, String title, String status, JsonObject update) {
        if (active.getAsBoolean()) delegate.onToolCall(toolCallId, title, status, update);
    }

    @Override
    public void onSubAgentEvent(String eventType, String agentName, String detail) {
        if (active.getAsBoolean()) delegate.onSubAgentEvent(eventType, agentName, detail);
    }

    @Override
    public void onScheduleEvent(String eventType, String detail, boolean expanded) {
        if (active.getAsBoolean()) delegate.onScheduleEvent(eventType, detail, expanded);
    }

    @Override
    public void onTalkToEvent(String eventType, String robotName, String content) {
        if (active.getAsBoolean()) delegate.onTalkToEvent(eventType, robotName, content);
    }

    @Override
    public void onCompactionEvent(String eventType, String provider) {
        if (active.getAsBoolean()) delegate.onCompactionEvent(eventType, provider);
    }

    @Override
    public void onComplete(String fullResponse) {
        if (active.getAsBoolean()) delegate.onComplete(fullResponse);
    }

    @Override
    public void onError(Exception error) {
        if (active.getAsBoolean()) delegate.onError(error);
    }
}
