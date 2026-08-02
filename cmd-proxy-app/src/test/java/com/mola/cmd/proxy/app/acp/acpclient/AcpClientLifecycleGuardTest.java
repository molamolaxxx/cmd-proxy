package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.acpclient.agent.KiroCliAgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import com.mola.cmd.proxy.app.acp.acpclient.listener.LifecycleGuardedAcpResponseListener;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class AcpClientLifecycleGuardTest {

    @Test
    public void closeInvalidatesCapturedGenerationAndBlocksStateRollback() throws Exception {
        TestClient client = new TestClient();
        client.markReady();
        long generation = client.captureGeneration();

        assertTrue(client.markBusy(generation));
        client.close();

        assertFalse(client.markReady(generation));
        assertEquals(AbstractAcpClient.State.CLOSED, client.getState());
        assertTrue(client.getLifecycleGeneration() > generation);
    }

    @Test
    public void duplicateCloseDoesNotAdvanceGenerationAgain() throws Exception {
        TestClient client = new TestClient();
        client.markReady();

        client.close();
        long closedGeneration = client.getLifecycleGeneration();
        client.close();

        assertEquals(closedGeneration, client.getLifecycleGeneration());
        assertEquals(AbstractAcpClient.State.CLOSED, client.getState());
    }

    @Test
    public void guardedListenerDropsAllLateCallbacks() {
        RecordingListener delegate = new RecordingListener();
        AtomicBoolean active = new AtomicBoolean(true);
        LifecycleGuardedAcpResponseListener listener =
                new LifecycleGuardedAcpResponseListener(delegate, active::get);

        listener.onMessage("before-close");
        active.set(false);
        listener.onMessage("late");
        listener.onToolCall("id", "tool", "completed", new JsonObject());
        listener.onComplete("late-complete");
        listener.onError(new RuntimeException("late-error"));
        listener.onTalkToEvent("TALK_TO_RECEIVE", "robot", "late-talk");
        listener.onScheduleEvent("SCHEDULE_CREATE", "late-schedule", true);
        listener.onSubAgentEvent("AGENT_COMPLETE", "agent", "late-subagent");
        listener.onCompactionEvent("COMPACTION_COMPLETED", "provider");

        assertEquals(1, delegate.callbacks.get());
    }

    private static final class TestClient extends AbstractAcpClient {
        private TestClient() {
            super(new KiroCliAgentProvider(), ".",
                    AcpClientIdentity.main("group-1", "Robot One", "Robot One"), null);
        }

        private void markReady() {
            state.set(State.READY);
        }

        private long captureGeneration() {
            return currentLifecycleGeneration();
        }

        private boolean markBusy(long generation) {
            return compareAndSetStateIfActive(generation, State.READY, State.BUSY);
        }

        private boolean markReady(long generation) {
            return setStateIfActive(generation, State.READY);
        }

        @Override
        protected void createSession() {
        }

        @Override
        protected long gracefulShutdownTimeoutMillis() {
            return 0;
        }
    }

    private static final class RecordingListener implements AcpResponseListener {
        private final AtomicInteger callbacks = new AtomicInteger();

        @Override
        public void onMessage(String text) {
            callbacks.incrementAndGet();
        }

        @Override
        public void onToolCall(String toolCallId, String title, String status, JsonObject update) {
            callbacks.incrementAndGet();
        }

        @Override
        public void onSubAgentEvent(String eventType, String agentName, String detail) {
            callbacks.incrementAndGet();
        }

        @Override
        public void onScheduleEvent(String eventType, String detail, boolean expanded) {
            callbacks.incrementAndGet();
        }

        @Override
        public void onTalkToEvent(String eventType, String robotName, String content) {
            callbacks.incrementAndGet();
        }

        @Override
        public void onCompactionEvent(String eventType, String provider) {
            callbacks.incrementAndGet();
        }

        @Override
        public void onComplete(String fullResponse) {
            callbacks.incrementAndGet();
        }

        @Override
        public void onError(Exception error) {
            callbacks.incrementAndGet();
        }
    }
}
