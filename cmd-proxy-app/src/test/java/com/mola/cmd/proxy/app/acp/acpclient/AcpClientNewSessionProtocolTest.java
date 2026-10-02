package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.action.ActionRuntimeRegistry;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Exercises the real send loop, runtime restart and second prompt using a tiny ACP process. */
public class AcpClientNewSessionProtocolTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void toolReturnsOnOldSessionThenFreshSessionReceivesPrompt() throws Exception {
        exercise(false);
    }

    @Test public void failedProviderTurnDoesNotRunAcceptedContinuation() throws Exception {
        exercise(true);
    }

    private void exercise(boolean fail) throws Exception {
        AcpClient client = new AcpClient(new ProtocolProvider(), temporary.newFolder().getAbsolutePath(),
                "protocol-" + java.util.UUID.randomUUID(), new AcpRobotParam());
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<String> oldSession = new AtomicReference<>();
        client.setNewSessionListener((old, current, prompt, id) -> {
            assertEquals(oldSession.get(), old);
            assertNotEquals(old, current);
            assertEquals("next-prompt", prompt);
            events.add("rotated");
        });
        client.setGlobalListener(new AcpResponseListener() {
            @Override public void onMessage(String text) { }
            @Override public void onToolCall(String id, String title, String status, JsonObject update) {
                try {
                    JsonObject args = new JsonObject(); args.addProperty("prompt", "next-prompt");
                    String result = ActionRuntimeRegistry.getInstance().execute(
                            client.getAuthSessionId(), "new_session", args);
                    assertTrue(result.contains("已接收"));
                    assertEquals(oldSession.get(), client.getSessionId());
                    events.add("accepted");
                } catch (Throwable failure) { error.set(failure); done.countDown(); }
            }
            @Override public void onComplete(String response) {
                events.add(response);
                if ("next-prompt".equals(response)) done.countDown();
            }
            @Override public void onError(Exception failure) {
                error.set(failure); done.countDown();
            }
        });
        try {
            client.start();
            oldSession.set(client.getSessionId());
            client.send(fail ? "first-fail" : "first", null);
            assertTrue("execution did not complete", done.await(20, TimeUnit.SECONDS));
            if (fail) {
                assertNotNull(error.get());
                assertEquals(Collections.singletonList("accepted"), events);
                assertEquals(oldSession.get(), client.getSessionId());
            } else {
                assertNull(error.get());
                assertEquals(java.util.Arrays.asList("accepted", "first", "rotated", "next-prompt"), events);
                assertEquals(1, client.getHistoryManager().getTurnCount());
            }
        } finally { client.close(); }
    }

    private static final class ProtocolProvider implements AgentProvider {
        @Override public String getName() { return "protocol-test"; }
        @Override public String getCommand() { return Paths.get(System.getProperty("java.home"), "bin", "java").toString(); }
        @Override public String[] getArgs() {
            return new String[]{"-cp", System.getProperty("java.class.path"), FakeAgent.class.getName()};
        }
        @Override public boolean supportsClientMcpServers() { return false; }
        @Override public List<Path> getMcpConfigPaths(String workspace) { return Collections.emptyList(); }
    }

    public static final class FakeAgent {
        public static void main(String[] args) throws Exception {
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = input.readLine()) != null) {
                JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                String method = request.get("method").getAsString();
                if ("session/end".equals(method)) return;
                if (!request.has("id")) continue;
                JsonObject result = new JsonObject();
                if ("session/new".equals(method)) result.addProperty("sessionId", java.util.UUID.randomUUID().toString());
                if ("session/prompt".equals(method)) {
                    String prompt = request.getAsJsonObject("params").getAsJsonArray("prompt")
                            .get(0).getAsJsonObject().get("text").getAsString();
                    boolean first = prompt.contains("first");
                    if (first) {
                        JsonObject update = new JsonObject();
                        update.addProperty("sessionUpdate", "tool_call");
                        update.addProperty("toolCallId", "test-tool");
                        update.addProperty("title", "request-continuation");
                        update.addProperty("status", "pending");
                        emitUpdate(request, update);
                    }
                    if (prompt.contains("first-fail")) {
                        JsonObject failure = new JsonObject(); failure.addProperty("code", -32000);
                        failure.addProperty("message", "planned error");
                        JsonObject response = response(request); response.add("error", failure);
                        System.out.println(response); System.out.flush(); continue;
                    }
                    JsonObject content = new JsonObject(); content.addProperty("type", "text");
                    content.addProperty("text", first ? "first" : "next-prompt");
                    JsonObject update = new JsonObject(); update.addProperty("sessionUpdate", "agent_message_chunk");
                    update.add("content", content); emitUpdate(request, update);
                    result.addProperty("stopReason", "end_turn");
                }
                JsonObject response = response(request); response.add("result", result);
                System.out.println(response); System.out.flush();
            }
        }
        private static JsonObject response(JsonObject request) {
            JsonObject response = new JsonObject(); response.addProperty("jsonrpc", "2.0");
            response.add("id", request.get("id")); return response;
        }
        private static void emitUpdate(JsonObject request, JsonObject update) {
            JsonObject params = new JsonObject(); params.add("sessionId", request.getAsJsonObject("params").get("sessionId"));
            params.add("update", update); JsonObject notification = new JsonObject();
            notification.addProperty("jsonrpc", "2.0"); notification.addProperty("method", "session/update");
            notification.add("params", params); System.out.println(notification); System.out.flush();
        }
    }
}
