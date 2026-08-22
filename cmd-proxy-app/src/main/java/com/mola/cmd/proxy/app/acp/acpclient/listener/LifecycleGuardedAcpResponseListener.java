package com.mola.cmd.proxy.app.acp.acpclient.listener;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 仅在所属 client lifecycle generation 仍有效时转发回调。
 * 关闭或替换 client 后，旧 turn 的迟到事件会被静默丢弃。
 */
public final class LifecycleGuardedAcpResponseListener implements AcpResponseListener {

    private static final Logger logger = LoggerFactory.getLogger(
            LifecycleGuardedAcpResponseListener.class);

    private final AcpResponseListener delegate;
    private final BooleanSupplier active;

    public LifecycleGuardedAcpResponseListener(AcpResponseListener delegate,
                                               BooleanSupplier active) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.active = Objects.requireNonNull(active, "active");
    }

    @Override
    public void markNextTermination(String termination) {
        delegate.markNextTermination(termination);
    }

    @Override
    public void clearNextTermination() {
        delegate.clearNextTermination();
    }

    @Override
    public void onMessage(String text) {
        forward("onMessage", () -> delegate.onMessage(text));
    }

    @Override
    public void onToolCall(String toolCallId, String title, String status, JsonObject update) {
        forward("onToolCall", () -> delegate.onToolCall(toolCallId, title, status, update));
    }

    @Override
    public void onSubAgentEvent(String eventType, String agentName, String detail) {
        forward("onSubAgentEvent", () -> delegate.onSubAgentEvent(eventType, agentName, detail));
    }

    @Override
    public void onScheduleEvent(String eventType, String detail, boolean expanded) {
        forward("onScheduleEvent", () -> delegate.onScheduleEvent(eventType, detail, expanded));
    }

    @Override
    public void onTalkToEvent(String eventType, String robotName, String content) {
        forward("onTalkToEvent", () -> delegate.onTalkToEvent(eventType, robotName, content));
    }

    @Override
    public void onCompactionEvent(String eventType, String provider) {
        forward("onCompactionEvent", () -> delegate.onCompactionEvent(eventType, provider));
    }

    @Override
    public void onComplete(String fullResponse) {
        forward("onComplete", () -> delegate.onComplete(fullResponse));
    }

    @Override
    public void onError(Exception error) {
        forward("onError", () -> delegate.onError(error));
    }

    private void forward(String callback, Runnable action) {
        if (!active.getAsBoolean()) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException error) {
            // Listener delivery is a side effect. It must not turn a healthy ACP
            // response into a client ERROR or terminate the ACP process.
            logger.warn("AcpResponseListener callback failed; ACP processing continues,"
                            + " callback={}, errorType={}, error={}",
                    callback, error.getClass().getSimpleName(), error.getMessage());
        }
    }
}
