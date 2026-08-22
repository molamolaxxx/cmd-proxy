package com.mola.cmd.proxy.app.acp.acpclient;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class MainAcpPromptCoordinatorTest {

    @Test
    public void readySendsImmediately() {
        MainAcpPromptCoordinator coordinator = new MainAcpPromptCoordinator();
        FakeClient client = new FakeClient(AbstractAcpClient.State.READY);

        PromptCommandResult result = coordinator.send(
                "g1", client, "hello", null, MainAcpPromptCoordinator.BusyPolicy.REJECT);

        assertResult(result, true, "SENT");
        assertEquals(Collections.singletonList("send:hello"), client.events);
        assertFalse(coordinator.hasPending("g1"));
    }

    @Test
    public void busyInterruptCancelsBeforePendingIsSentOnRealReady() {
        MainAcpPromptCoordinator coordinator = new MainAcpPromptCoordinator();
        FakeClient client = new FakeClient(AbstractAcpClient.State.BUSY);

        PromptCommandResult result = coordinator.send(
                "g1", client, "next", null,
                MainAcpPromptCoordinator.BusyPolicy.INTERRUPT);

        assertResult(result, true, "INTERRUPTED_PENDING");
        assertEquals(asList("mark:INTERRUPTED", "cancel"), client.events);
        assertTrue(coordinator.hasPending("g1"));

        client.state = AbstractAcpClient.State.READY;
        coordinator.onReady("g1", client);
        assertEquals(asList("mark:INTERRUPTED", "cancel", "send:next"), client.events);
        assertFalse(coordinator.hasPending("g1"));
    }

    @Test
    public void busyWithoutInterruptIsRejected() {
        MainAcpPromptCoordinator coordinator = new MainAcpPromptCoordinator();
        FakeClient client = new FakeClient(AbstractAcpClient.State.BUSY);

        PromptCommandResult result = coordinator.send(
                "g1", client, "next", null, MainAcpPromptCoordinator.BusyPolicy.REJECT);

        assertResult(result, false, "REJECTED_STATE");
        assertTrue(client.events.isEmpty());
        assertFalse(coordinator.hasPending("g1"));
    }

    @Test
    public void cancelFailureDoesNotCreateOrReplacePending() {
        MainAcpPromptCoordinator coordinator = new MainAcpPromptCoordinator();
        FakeClient client = new FakeClient(AbstractAcpClient.State.BUSY);
        coordinator.send("g1", client, "first", null,
                MainAcpPromptCoordinator.BusyPolicy.INTERRUPT);
        client.failCancel = true;

        PromptCommandResult failed = coordinator.send(
                "g1", client, "lost", null, MainAcpPromptCoordinator.BusyPolicy.INTERRUPT);

        assertResult(failed, false, "CANCEL_FAILED");
        client.failCancel = false;
        client.state = AbstractAcpClient.State.READY;
        coordinator.onReady("g1", client);
        assertEquals(asList("mark:INTERRUPTED", "cancel",
                "cancel", "send:first"), client.events);
    }

    @Test
    public void latestBusyInterruptWinsSinglePendingSlot() {
        MainAcpPromptCoordinator coordinator = new MainAcpPromptCoordinator();
        FakeClient client = new FakeClient(AbstractAcpClient.State.BUSY);

        coordinator.send("g1", client, "first", null,
                MainAcpPromptCoordinator.BusyPolicy.INTERRUPT);
        coordinator.send("g1", client, "latest", null,
                MainAcpPromptCoordinator.BusyPolicy.INTERRUPT);
        client.state = AbstractAcpClient.State.READY;
        coordinator.onReady("g1", client);

        assertEquals(asList("mark:INTERRUPTED", "cancel",
                "cancel", "send:latest"), client.events);
    }

    @Test
    public void directReadySendSupersedesPendingBeforeReadyCallbackDrains() {
        MainAcpPromptCoordinator coordinator = new MainAcpPromptCoordinator();
        FakeClient client = new FakeClient(AbstractAcpClient.State.BUSY);
        coordinator.send("g1", client, "pending", null,
                MainAcpPromptCoordinator.BusyPolicy.INTERRUPT);

        client.state = AbstractAcpClient.State.READY;
        PromptCommandResult result = coordinator.send(
                "g1", client, "newest", null, MainAcpPromptCoordinator.BusyPolicy.REJECT);
        coordinator.onReady("g1", client);

        assertResult(result, true, "SENT");
        assertEquals(asList("mark:INTERRUPTED", "cancel", "send:newest"), client.events);
        assertFalse(coordinator.hasPending("g1"));
    }

    @Test
    public void lifecycleClearDropsPendingAndOldClientCannotDeliver() {
        MainAcpPromptCoordinator coordinator = new MainAcpPromptCoordinator();
        FakeClient oldClient = new FakeClient(AbstractAcpClient.State.BUSY);
        coordinator.send("g1", oldClient, "stale", null,
                MainAcpPromptCoordinator.BusyPolicy.INTERRUPT);

        coordinator.clear("g1");
        oldClient.state = AbstractAcpClient.State.READY;
        coordinator.onReady("g1", oldClient);

        assertEquals(asList("mark:INTERRUPTED", "cancel"), oldClient.events);
        assertFalse(coordinator.hasPending("g1"));
    }

    @Test
    public void providerFailureAfterInterruptKeepsPendingForReadyRecovery() {
        MainAcpPromptCoordinator coordinator = new MainAcpPromptCoordinator();
        FakeClient client = new FakeClient(AbstractAcpClient.State.BUSY);
        coordinator.send("g1", client, "must-survive", null,
                MainAcpPromptCoordinator.BusyPolicy.INTERRUPT);

        // Provider 把 session/cancel 表现成异常时，registry 以该判断决定恢复 READY。
        assertTrue(coordinator.hasPending("g1", client));
        client.state = AbstractAcpClient.State.READY;
        coordinator.onReady("g1", client);

        assertEquals(asList("mark:INTERRUPTED", "cancel", "send:must-survive"), client.events);
        assertFalse(coordinator.hasPending("g1"));
    }

    @Test
    public void manualCancelIsStateAwareAndIdempotentWhenReady() {
        MainAcpPromptCoordinator coordinator = new MainAcpPromptCoordinator();
        FakeClient client = new FakeClient(AbstractAcpClient.State.READY);

        assertResult(coordinator.cancel(client), true, "NOOP");
        assertTrue(client.events.isEmpty());

        client.state = AbstractAcpClient.State.BUSY;
        assertResult(coordinator.cancel(client), true, "CANCELED");
        assertEquals(Collections.singletonList("cancel"), client.events);
    }

    private static void assertResult(PromptCommandResult result,
                                     boolean accepted, String code) {
        assertEquals(accepted, result.isAccepted());
        assertEquals(code, result.getCode());
        assertNotNull(result.getResult());
    }

    private static List<String> asList(String... values) {
        return java.util.Arrays.asList(values);
    }

    private static final class FakeClient implements MainAcpPromptCoordinator.ClientPort {
        private final Object identity = new Object();
        private final List<String> events = new ArrayList<>();
        private AbstractAcpClient.State state;
        private boolean failCancel;

        private FakeClient(AbstractAcpClient.State state) {
            this.state = state;
        }

        @Override public Object identity() { return identity; }
        @Override public AbstractAcpClient.State state() { return state; }

        @Override
        public void send(String message, List<Map<String, String>> files) {
            events.add("send:" + message);
            state = AbstractAcpClient.State.BUSY;
        }

        @Override
        public void cancel() throws IOException {
            events.add("cancel");
            if (failCancel) throw new IOException("cancel failed");
        }

        @Override
        public void markNextTermination(String termination) {
            events.add("mark:" + termination);
        }

        @Override
        public void clearNextTermination() {
            events.add("clear");
        }
    }
}
