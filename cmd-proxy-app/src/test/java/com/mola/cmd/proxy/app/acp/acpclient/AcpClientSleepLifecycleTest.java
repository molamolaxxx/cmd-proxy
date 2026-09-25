package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class AcpClientSleepLifecycleTest {
    @Test
    public void idleClientReleasesRuntimeAndWakesInPlace() throws Exception {
        FakeClient client = new FakeClient();
        RecordingListener listener = new RecordingListener();
        client.setGlobalListener(listener);
        client.readyAt(1_000L);

        assertTrue(client.sleepIfIdle(61_000L, 60_000L));
        assertEquals(AbstractAcpClient.State.SLEEP, client.getState());
        assertEquals(1, client.releases);

        assertTrue(client.wakeIfSleeping());
        assertEquals(AbstractAcpClient.State.READY, client.getState());
        assertEquals(1, client.wakes);
        assertEquals("AGENT_WAKE:SLEEP:READY:false", listener.lifecycleEvent);
        assertFalse(client.wakeIfSleeping());
    }

    @Test
    public void sleepingClientDefersAutomaticNewSession() throws Exception {
        FakeClient client = new FakeClient();
        RecordingListener listener = new RecordingListener();
        client.setGlobalListener(listener);
        client.readyAt(1_000L);
        assertTrue(client.sleepIfIdle(61_000L, 60_000L));
        assertTrue(client.markNewSessionOnWake());
        assertEquals(AbstractAcpClient.State.SLEEP, client.getState());
        assertTrue(client.wakeIfSleeping());
        assertEquals("AGENT_WAKE:SLEEP:READY:true", listener.lifecycleEvent);
    }

    @Test
    public void wakeRotationNotifiesSessionRotationListenerWithOldAndNewSession()
            throws Exception {
        FakeClient client = new FakeClient();
        client.readyAt(1_000L);
        client.setSessionId("session-old");
        java.util.List<String> rotations = new java.util.ArrayList<>();
        client.setSessionRotationListener((previous, current) ->
                rotations.add(previous + "->" + current));
        assertTrue(client.sleepIfIdle(61_000L, 60_000L));
        assertTrue(client.markNewSessionOnWake());
        assertTrue(client.wakeIfSleeping());
        assertEquals(java.util.Collections.singletonList("session-old->session-1"), rotations);
    }

    private static final class RecordingListener implements AcpResponseListener {
        private String lifecycleEvent;

        @Override public void onMessage(String text) { }
        @Override public void onToolCall(String toolCallId, String title, String status,
                                         com.google.gson.JsonObject update) { }
        @Override public void onComplete(String fullResponse) { }
        @Override public void onError(Exception error) { }

        @Override
        public void onLifecycleEvent(String eventType, String fromState, String toState,
                                     long durationMillis, boolean newSession) {
            lifecycleEvent = eventType + ":" + fromState + ":" + toState + ":" + newSession;
        }
    }

    private static final class FakeClient extends AcpClient {
        private int releases;
        private int wakes;

        private FakeClient() {
            super(new FakeProvider(), "/tmp", "sleep-test", new AcpRobotParam());
        }

        private void readyAt(long timestamp) {
            state.set(State.READY);
            markActivityAt(timestamp);
        }

        @Override protected void releaseRuntimeForSleep() { releases++; }

        @Override protected void wakeRuntimeSession() throws IOException {
            if (!state.compareAndSet(State.SLEEP, State.READY)) {
                throw new IOException("unexpected state");
            }
            wakes++;
            setSessionId("session-" + wakes);
        }
    }

    private static final class FakeProvider implements AgentProvider {
        @Override public String getName() { return "fake"; }
        @Override public String getCommand() { return "fake"; }
        @Override public String[] getArgs() { return new String[0]; }
        @Override public List<Path> getMcpConfigPaths(String workspacePath) {
            return Collections.emptyList();
        }
    }
}
