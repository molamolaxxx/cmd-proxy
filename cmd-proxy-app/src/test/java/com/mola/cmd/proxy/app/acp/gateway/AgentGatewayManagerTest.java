package com.mola.cmd.proxy.app.acp.gateway;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.gateway.model.AgentGatewayConfig;
import com.mola.cmd.proxy.app.acp.gateway.model.AgentGatewayServerConfig;
import com.mola.cmd.proxy.app.acp.gateway.model.GatewayTarget;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class AgentGatewayManagerTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void authenticatesSendsOnceAndExposesReplay() throws Exception {
        AgentGatewayConfig config = config();
        FakeRuntime runtime = new FakeRuntime();
        GatewayEventJournal journal = new GatewayEventJournal(
                temporaryFolder.newFile("manager.db").toPath());
        try (AgentGatewayManager manager = new AgentGatewayManager(
                new AgentGatewayServerConfig(), Collections.singletonList(config), runtime, journal)) {
            assertEquals(config, manager.authenticate("Bearer " + config.getAuthCode()));
            JSONObject status = manager.status(config);
            JSONObject session = status.getJSONObject("data").getJSONObject("session");

            JSONObject request = new JSONObject(true);
            request.put("expectedSessionId", session.getString("sessionId"));
            request.put("expectedEpoch", session.getLongValue("epoch"));
            request.put("message", "hello");
            JSONObject first = manager.send(config, request, Collections.emptyList(), "send-1");
            JSONObject replay = manager.send(config, request, Collections.emptyList(), "send-1");

            assertEquals(first.getString("requestId"), replay.getString("requestId"));
            assertEquals(first.getJSONObject("data").getString("turnId"),
                    replay.getJSONObject("data").getString("turnId"));
            assertEquals(1, runtime.sendCalls.get());
            JSONArray events = manager.events(config, "session-1", 1L, 0L, 100);
            assertEquals(1, events.size());
            assertEquals("user.message.accepted", events.getJSONObject(0).getString("type"));
            assertEquals("hello", events.getJSONObject(0).getJSONObject("event")
                    .getJSONObject("payload").getString("text"));
        }
    }

    @Test
    public void rejectsWrongTokenAndStaleSessionEpoch() throws Exception {
        AgentGatewayConfig config = config();
        try (AgentGatewayManager manager = new AgentGatewayManager(
                new AgentGatewayServerConfig(), Collections.singletonList(config), new FakeRuntime(),
                new GatewayEventJournal(temporaryFolder.newFile("guards.db").toPath()))) {
            try {
                manager.authenticate("Bearer wrong-token-that-is-long-enough");
                fail("expected invalid token");
            } catch (GatewayException expected) {
                assertEquals(401, expected.getHttpStatus());
            }

            JSONObject stale = new JSONObject(true);
            stale.put("expectedSessionId", "old-session");
            stale.put("expectedEpoch", 1L);
            stale.put("message", "hello");
            try {
                manager.send(config, stale, Collections.emptyList(), "send-stale");
                fail("expected session conflict");
            } catch (GatewayException expected) {
                assertEquals(409, expected.getHttpStatus());
                assertEquals("SESSION_CONFLICT", expected.getCode());
            }
        }
    }

    @Test
    public void restoresEpochFromDurableEventsAfterRestart() throws Exception {
        AgentGatewayConfig config = config();
        FakeRuntime runtime = new FakeRuntime();
        Path database = temporaryFolder.newFile("restart.db").toPath();
        try (AgentGatewayManager first = new AgentGatewayManager(
                new AgentGatewayServerConfig(), Collections.singletonList(config), runtime,
                new GatewayEventJournal(database))) {
            JSONObject status = first.status(config).getJSONObject("data").getJSONObject("session");
            JSONObject request = new JSONObject(true);
            request.put("expectedSessionId", status.getString("sessionId"));
            request.put("expectedEpoch", status.getLongValue("epoch"));
            request.put("message", "persist cursor");
            first.send(config, request, Collections.emptyList(), "persist-send");
        }

        try (AgentGatewayManager restarted = new AgentGatewayManager(
                new AgentGatewayServerConfig(), Collections.singletonList(config), runtime,
                new GatewayEventJournal(database))) {
            JSONObject session = restarted.status(config).getJSONObject("data")
                    .getJSONObject("session");
            assertEquals("session-1", session.getString("sessionId"));
            assertEquals(1L, session.getLongValue("epoch"));
            assertEquals(1L, session.getLongValue("lastEventSeq"));
        }
    }

    private static AgentGatewayConfig config() {
        return JSON.parseObject("{\"id\":\"gateway-1\",\"name\":\"Gateway One\","
                + "\"enabled\":true,\"authCode\":\"0123456789abcdef0123456789abcdef\","
                + "\"target\":{\"type\":\"STARWEAVE_MAIN\",\"groupId\":\"group-1\"}}",
                AgentGatewayConfig.class);
    }

    private static final class FakeRuntime implements GatewayRuntime {
        private final AtomicInteger sendCalls = new AtomicInteger();
        private String sessionId = "session-1";

        @Override public JSONArray targets() { return new JSONArray(); }
        @Override public JSONObject status(GatewayTarget target) {
            JSONObject value = accepted("OK");
            value.put("sessionId", sessionId);
            value.put("state", "READY");
            value.put("displayName", "Agent One");
            value.put("surface", "STARWEAVE");
            return value;
        }
        @Override public JSONObject send(GatewayTarget target, String message,
                                         List<Map<String, String>> files, String busyPolicy) {
            sendCalls.incrementAndGet();
            return accepted("PROMPT_ACCEPTED");
        }
        @Override public JSONObject cancel(GatewayTarget target) {
            return accepted("CANCEL_REQUESTED");
        }
        @Override public JSONObject newSession(GatewayTarget target) {
            sessionId = "session-2";
            return accepted("SESSION_CREATED");
        }
        private static JSONObject accepted(String code) {
            JSONObject value = new JSONObject(true);
            value.put("accepted", true);
            value.put("code", code);
            value.put("message", code);
            return value;
        }
    }
}
