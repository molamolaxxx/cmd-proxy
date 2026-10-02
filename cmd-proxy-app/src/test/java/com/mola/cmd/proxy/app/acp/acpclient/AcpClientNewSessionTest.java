package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.action.ActionRuntimeRegistry;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class AcpClientNewSessionTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private FakeClient client;

    @After public void cleanup() throws Exception {
        if (client != null) client.close();
    }

    @Test public void defersRotationAndRunsOnceBeforeQueuedWork() throws Exception {
        client = activeClient();
        long generation = client.getLifecycleGeneration();
        String accepted = request("继续工作");
        assertTrue(accepted.contains("已接收"));
        assertEquals("old", client.getSessionId());
        assertTrue(client.events.isEmpty());
        client.setAfterTurnReady(() -> client.events.add("queued"));
        client.setNewSessionListener((old, current, prompt, id) -> {
            assertEquals("old", old);
            assertEquals("new", current);
            assertEquals("继续工作", prompt);
            assertNotNull(id);
            client.events.add("projection");
        });
        client.finishSuccessfulTurn(generation, PromptOptions.defaults());
        assertEquals(Arrays.asList("restart", "projection", "run:继续工作"), client.events);
        assertEquals(AbstractAcpClient.State.BUSY, client.getState());
        client.finishSuccessfulTurn(generation, PromptOptions.defaults());
        assertEquals(3, client.events.size());
    }

    @Test public void rejectsDuplicateWithoutReplacingFirstPrompt() throws Exception {
        client = activeClient();
        request("first");
        assertRejected("second", "已有待执行");
        client.finishSuccessfulTurn(client.getLifecycleGeneration(), PromptOptions.defaults());
        assertEquals("run:first", client.events.get(1));
    }

    @Test public void validatesSingleStringParameter() throws Exception {
        client = activeClient();
        for (JsonObject args : Arrays.asList(new JsonObject(), args("  "), args("valid"))) {
            if (args.has("prompt") && "valid".equals(args.get("prompt").getAsString())) {
                args.addProperty("extra", true);
            }
            try {
                execute(args);
                fail("invalid arguments admitted");
            } catch (IllegalArgumentException expected) { }
        }
        JsonObject numeric = new JsonObject();
        numeric.addProperty("prompt", 123);
        try { execute(numeric); fail(); } catch (IllegalArgumentException expected) { }
        assertTrue(client.events.isEmpty());
    }

    @Test public void requiresActiveTurn() throws Exception {
        client = activeClient();
        client.state.set(AbstractAcpClient.State.READY);
        assertRejected("first", "NO_ACTIVE_TURN");
    }

    @Test public void cancellationDiscardsRequestAndRejectsLaterCallsInSameTurn() throws Exception {
        client = activeClient();
        request("first");
        client.cancel();
        assertRejected("later", "已被取消");
        client.finishSuccessfulTurn(client.getLifecycleGeneration(), PromptOptions.defaults());
        assertTrue(client.events.isEmpty());
        assertEquals(AbstractAcpClient.State.READY, client.getState());
    }

    @Test public void closingOrStaleGenerationNeverStartsContinuation() throws Exception {
        client = activeClient();
        request("first");
        long generation = client.getLifecycleGeneration();
        client.lifecycleBoundary();
        client.finishSuccessfulTurn(generation, PromptOptions.defaults());
        assertTrue(client.events.isEmpty());
        client.close();
        client.finishSuccessfulTurn(client.getLifecycleGeneration(), PromptOptions.defaults());
        assertEquals(AbstractAcpClient.State.CLOSED, client.getState());
    }

    @Test public void startupFailureReportsErrorAndNeverSendsPrompt() throws Exception {
        client = activeClient();
        client.failRestart = true;
        request("first");
        client.finishSuccessfulTurn(client.getLifecycleGeneration(), PromptOptions.defaults());
        assertEquals(Collections.singletonList("restart"), client.events);
        assertEquals(AbstractAcpClient.State.ERROR, client.getState());
        assertTrue(client.error.getMessage().contains("创建新会话"));
    }

    @Test public void projectionFailurePreventsRunningAgainstUnsynchronizedSession() throws Exception {
        client = activeClient();
        client.setNewSessionListener((old, current, prompt, id) -> {
            throw new IllegalStateException("projection unavailable");
        });
        request("first");
        client.finishSuccessfulTurn(client.getLifecycleGeneration(), PromptOptions.defaults());
        assertEquals(Collections.singletonList("restart"), client.events);
        assertEquals(AbstractAcpClient.State.ERROR, client.getState());
    }

    @Test public void cancellationDuringProjectionLeavesReadyAndDrainsQueuedWork() throws Exception {
        client = activeClient();
        client.setAfterTurnReady(() -> client.events.add("queued"));
        client.setNewSessionListener((old, current, prompt, id) -> {
            try { client.cancel(); } catch (IOException failure) { throw new RuntimeException(failure); }
        });
        request("first");
        client.finishSuccessfulTurn(client.getLifecycleGeneration(), PromptOptions.defaults());
        assertEquals(Arrays.asList("restart", "queued"), client.events);
        assertEquals(AbstractAcpClient.State.READY, client.getState());
    }

    @Test public void newOptionsRetainTaskButDoNotRepeatScheduleCompletionOrMessageId() {
        PromptOptions old = new PromptOptions().setScheduleExecution(true)
                .setScheduleExecutionId("execution").setClientMessageId("old-message")
                .setTaskContext("task", "event", 1L, 2L, 3L, true);
        PromptOptions next = old.forNewSession();
        assertEquals("task", next.getTaskId());
        assertEquals(Long.valueOf(3L), next.getTaskContentVersion());
        assertFalse(next.isTaskControl());
        assertFalse(next.isScheduleExecution());
        assertNull(next.getScheduleExecutionId());
        assertNotEquals(old.getClientMessageId(), next.getClientMessageId());
        assertNotEquals(old.getAuthTurnId(), next.getAuthTurnId());
    }

    @Test public void newOptionsRetainChannelAuthorityWithFreshReplyCounters() {
        com.mola.cmd.proxy.app.acp.channel.model.ChannelTurnContext context =
                new com.mola.cmd.proxy.app.acp.channel.model.ChannelTurnContext(
                        "channel", "reply-target", "single", "conversation", "sender", "Sender");
        PromptOptions old = new PromptOptions().setChannelTurnContext(context);
        old.markChannelReplyAttempt();
        old.closeChannelTurnOnce();
        PromptOptions next = old.forNewSession();
        assertSame(context, next.getChannelTurnContext());
        assertSame(old.getAuthPrincipalContext(), next.getAuthPrincipalContext());
        assertFalse(next.hasChannelReplyAttempt());
        assertTrue(next.closeChannelTurnOnce());
    }

    private FakeClient activeClient() throws Exception {
        FakeClient result = new FakeClient(temporary.newFolder().getAbsolutePath());
        result.setSessionId("old");
        result.state.set(AbstractAcpClient.State.BUSY);
        setReference(result, "activeMcpOptions", PromptOptions.defaults());
        setReference(result, "activeMcpListener", new NoopListener());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void setReference(AcpClient target, String name, Object value) throws Exception {
        Field field = AcpClient.class.getDeclaredField(name);
        field.setAccessible(true);
        ((AtomicReference<Object>) field.get(target)).set(value);
    }

    private String request(String prompt) throws Exception { return execute(args(prompt)); }
    private String execute(JsonObject args) throws Exception {
        return ActionRuntimeRegistry.getInstance().execute(client.getAuthSessionId(), "new_session", args);
    }
    private static JsonObject args(String prompt) {
        JsonObject args = new JsonObject(); args.addProperty("prompt", prompt); return args;
    }
    private void assertRejected(String prompt, String message) throws Exception {
        try { request(prompt); fail("request admitted"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains(message)); }
    }

    private static final class FakeClient extends AcpClient {
        final List<String> events = new ArrayList<>();
        boolean failRestart;
        Exception error;
        FakeClient(String workspace) {
            super(new FakeProvider(), workspace, "new-session-test", new AcpRobotParam());
            setGlobalListener(new NoopListener() {
                @Override public void onError(Exception failure) { error = failure; }
            });
        }
        @Override protected void restartForNewSession() throws IOException {
            events.add("restart");
            if (failRestart) throw new IOException("startup failed");
            lifecycleBoundary();
            setSessionId("new");
            state.set(State.READY);
        }
        @Override public void send(String prompt, List<java.util.Map<String, String>> files,
                                   PromptOptions options) {
            events.add("run:" + prompt);
            state.set(State.BUSY);
        }
        @Override protected void sendJson(JsonObject request) { }
    }
    private static class NoopListener implements AcpResponseListener {
        @Override public void onToolCall(String id, String title, String status, JsonObject update) { }
        @Override public void onMessage(String text) { }
        @Override public void onComplete(String text) { }
        @Override public void onError(Exception failure) { }
    }
    private static final class FakeProvider implements AgentProvider {
        @Override public String getName() { return "new-session-test"; }
        @Override public String getCommand() { return "unused"; }
        @Override public String[] getArgs() { return new String[0]; }
        @Override public List<Path> getMcpConfigPaths(String workspace) { return Collections.emptyList(); }
    }
}
