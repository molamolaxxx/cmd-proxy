package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import org.junit.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class AcpClientInterruptedFailureRecoveryTest {

    @Test
    public void interruptedProviderFailureEndsOldTurnThenRecoversReadyWithoutClearingPending() {
        TestClient client = new TestClient();
        List<String> events = new ArrayList<>();
        client.markBusy();
        client.setRecoverTurnFailureToReady(() -> true);
        client.setAfterTurnReady(() -> events.add("ready"));
        client.setAfterTurnFailed(() -> events.add("failed"));

        boolean recovered = client.recoverInterruptedTurnFailure(
                client.generation(), listener(events), new RuntimeException("cancelled"));

        assertTrue(recovered);
        assertEquals(java.util.Arrays.asList("error", "ready"), events);
        assertEquals(AbstractAcpClient.State.READY, client.getState());
    }

    @Test
    public void ordinaryProviderFailureIsNotRecoveredWithoutInterruptPending() {
        TestClient client = new TestClient();
        client.markBusy();
        client.setRecoverTurnFailureToReady(() -> false);

        assertFalse(client.recoverInterruptedTurnFailure(
                client.generation(), listener(new ArrayList<>()),
                new RuntimeException("provider failed")));
        assertEquals(AbstractAcpClient.State.BUSY, client.getState());
    }

    private static AcpResponseListener listener(List<String> events) {
        return new AcpResponseListener() {
            @Override public void onMessage(String text) { }
            @Override public void onToolCall(String id, String title, String status,
                                             JsonObject update) { }
            @Override public void onComplete(String response) { events.add("complete"); }
            @Override public void onError(Exception error) { events.add("error"); }
        };
    }

    private static final class TestClient extends AcpClient {
        private TestClient() {
            super(new FakeProvider(), System.getProperty("java.io.tmpdir"),
                    "interrupt-test", robot());
        }

        private static AcpRobotParam robot() {
            AcpRobotParam robot = new AcpRobotParam();
            robot.setName("interrupt-test");
            return robot;
        }

        private void markBusy() { state.set(State.BUSY); }
        private long generation() { return currentLifecycleGeneration(); }
    }

    private static final class FakeProvider implements AgentProvider {
        @Override public String getCommand() { return "unused"; }
        @Override public String[] getArgs() { return new String[0]; }
        @Override public List<Path> getMcpConfigPaths(String workspacePath) {
            return Collections.emptyList();
        }
        @Override public String getName() { return "fake"; }
        @Override public double extractContextUsage(JsonObject msg) { return -1; }
    }
}
