package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.acpclient.agent.KiroCliAgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext;
import com.mola.cmd.proxy.app.acp.talkto.*;
import com.mola.cmd.proxy.app.acp.talkto.model.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.Assert.*;

public class AcpClientTalkToCircuitLifecycleTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void nativeToolFanoutKeepsOneCascadeAndEmitsOneTerminationCard() throws Exception {
        Harness h = new Harness();
        Method tool = AcpClient.class.getDeclaredMethod("executeMcpTalkTo", JsonObject.class);
        tool.setAccessible(true);
        PromptOptions options = PromptOptions.defaults();
        h.client.state.set(AbstractAcpClient.State.BUSY);
        bind(h.client, "activeMcpOptions", options);
        bind(h.client, "activeMcpListener", h.listener);
        String result = null;
        for (int i = 0; i < 6; i++) {
            JsonObject args = new JsonObject();
            args.addProperty("target", "member-" + i);
            args.addProperty("content", "different message " + i);
            args.addProperty("_depth", i % 2 == 0 ? 0 : 999);
            result = (String) tool.invoke(h.client, args);
        }
        assertTrue(result.startsWith("[TALK_TO_CIRCUIT_OPENED]"));
        assertEquals(2, Collections.frequency(h.events, "TALK_TO_SEND"));
        assertEquals(1, Collections.frequency(h.events, "TALK_TO_CIRCUIT_OPENED"));
        assertEquals(2, h.dispatcher.accepted);
        assertEquals("", h.protocol.toString());
    }

    @Test
    public void legacyCapturedActionStopsWithoutSendingAFollowupPrompt() throws Exception {
        Harness h = new Harness();
        PromptOptions options = PromptOptions.defaults();
        options.addTalkToParent("sender", new TalkToTrace(null, null, null, 5,
                System.currentTimeMillis()));
        Method direct = AcpClient.class.getDeclaredMethod("handleTalkToDirect",
                String.class, String.class, AcpResponseListener.class, PromptOptions.class);
        direct.setAccessible(true);
        assertEquals(true, direct.invoke(h.client,
                "{\"action\":\"talk_to\",\"target\":\"third-member\",\"content\":\"forward\",\"_depth\":0}",
                "", h.listener, options));
        assertEquals(0, h.dispatcher.accepted);
        assertEquals(Arrays.asList("TALK_TO_CIRCUIT_OPENED", "complete"), h.events);
        assertEquals("No session/prompt may be emitted after circuit opening", "", h.protocol.toString());
    }

    @Test
    public void remoteCircuitControlProjectsOnceWithoutStartingAPrompt() throws Exception {
        Harness h = new Harness();
        h.client.setGlobalListener(h.listener);
        TalkToTrace trace = new TalkToTrace(null, null, null, 2,
                System.currentTimeMillis());

        h.dispatcher.openRemoteCircuit(h.client, trace, "REMOTE_LIMIT");
        h.dispatcher.openRemoteCircuit(h.client, trace, "REMOTE_LIMIT");

        assertEquals(Collections.singletonList("TALK_TO_CIRCUIT_OPENED"), h.events);
        assertEquals("", h.protocol.toString());
    }

    @SuppressWarnings("unchecked")
    private static void bind(AcpClient client, String name, Object value) throws Exception {
        java.lang.reflect.Field field = AcpClient.class.getDeclaredField(name);
        field.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicReference<Object>) field.get(client)).set(value);
    }

    private class Harness {
        final StringWriter protocol = new StringWriter();
        final List<String> events = new ArrayList<>();
        final BudgetDispatcher dispatcher = new BudgetDispatcher();
        final AcpClient client;
        final AcpResponseListener listener = new AcpResponseListener() {
            public void onMessage(String text) { }
            public void onToolCall(String id, String title, String status, JsonObject update) { }
            public void onComplete(String text) { events.add("complete"); }
            public void onError(Exception error) { throw new AssertionError(error); }
            public void onTalkToEvent(String type, String peer, String content) { events.add(type); }
        };
        Harness() throws Exception {
            AcpClientIdentity identity = AcpClientIdentity.main("circuit-group", "Robot", "Robot");
            ConversationHistoryManager history = new ConversationHistoryManager(identity,
                    temporary.newFolder().toPath());
            client = new AcpClient(new KiroCliAgentProvider(), ".", identity, null, history);
            client.writer = new BufferedWriter(protocol);
            client.sessionId = "circuit-session";
            client.setTalkToSupport(dispatcher, new TalkToContextInjector(), Collections.emptyMap());
        }
    }

    private static class BudgetDispatcher extends TalkToDispatcher {
        int accepted;
        BudgetDispatcher() {
            super(Collections.emptyMap(), AcpClientRegistry.getInstance(), Collections.emptyMap());
        }
        @Override public String deliver(TalkToRequest request, String sender, String chatter,
                String group, List<ContactRef> contacts, AuthPrincipalContext principal) {
            TalkToCircuitBreaker.Admission admission = circuitBreaker.admit(
                    request.getParentTrace(), sender, request.getTarget());
            if (!admission.isAccepted()) return circuitOpenResult(admission, sender, request.getTarget());
            accepted++;
            return "[talkTo 结果]\n已成功投递";
        }
    }
}
