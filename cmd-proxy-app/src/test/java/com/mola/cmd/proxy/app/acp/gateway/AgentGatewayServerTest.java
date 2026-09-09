package com.mola.cmd.proxy.app.acp.gateway;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.gateway.model.AgentGatewayConfig;
import com.mola.cmd.proxy.app.acp.gateway.model.AgentGatewayServerConfig;
import com.mola.cmd.proxy.app.acp.gateway.model.GatewayTarget;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AgentGatewayServerTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void servesAuthenticatedHttpAndWebSocketSessionCommands() throws Exception {
        AgentGatewayServerConfig serverConfig = new AgentGatewayServerConfig();
        serverConfig.setEnabled(true);
        serverConfig.setBindHost("127.0.0.1");
        serverConfig.setPort(0);
        AgentGatewayConfig gateway = gateway();
        AgentGatewayManager manager = new AgentGatewayManager(serverConfig,
                Collections.singletonList(gateway), new Runtime(), new GatewayEventJournal(
                temporaryFolder.newFile("server.db").toPath()));
        AgentGatewayServer server = new AgentGatewayServer(manager);
        WebSocketClient client = new WebSocketClient();
        try {
            server.start();
            int port = server.getPort();
            JSONObject http = get(port, gateway.getAuthCode());
            assertEquals("OK", http.getString("code"));
            assertEquals("session-1", http.getJSONObject("data").getJSONObject("session")
                    .getString("sessionId"));

            client.start();
            CapturingSocket socket = new CapturingSocket();
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            request.setHeader("Authorization", "Bearer " + gateway.getAuthCode());
            request.setSubProtocols("cmd-proxy.agent-gateway.v1");
            Session session = client.connect(socket,
                    new URI("ws://127.0.0.1:" + port + AgentGatewayServer.PREFIX + "/ws"),
                    request).get(5, TimeUnit.SECONDS);
            JSONObject hello = socket.next();
            assertEquals("server.hello", hello.getString("type"));
            assertEquals("cmd-proxy.agent-gateway.v1", session.getUpgradeResponse()
                    .getAcceptedSubProtocol());

            session.getRemote().sendString("{\"schemaVersion\":\"1.0\","
                    + "\"frameType\":\"command\",\"requestId\":\"req-1\","
                    + "\"type\":\"session.get\",\"payload\":{}}");
            JSONObject result = socket.next();
            assertEquals("command.result", result.getString("frameType"));
            assertEquals("session.get.result", result.getString("type"));
            assertTrue(result.getJSONObject("payload").getBooleanValue("accepted"));
            session.close();
        } finally {
            try { client.stop(); } catch (Exception ignored) { }
            server.close();
            manager.close();
        }
    }

    private static JSONObject get(int port, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + AgentGatewayServer.PREFIX + "/session")
                .openConnection();
        connection.setRequestProperty("Authorization", "Bearer " + token);
        assertEquals(200, connection.getResponseCode());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) body.append(line);
            return JSON.parseObject(body.toString());
        } finally { connection.disconnect(); }
    }

    private static AgentGatewayConfig gateway() {
        return JSON.parseObject("{\"id\":\"gateway-1\",\"name\":\"Gateway One\","
                + "\"enabled\":true,\"authCode\":\"0123456789abcdef0123456789abcdef\","
                + "\"target\":{\"type\":\"STARWEAVE_MAIN\",\"groupId\":\"group-1\"}}",
                AgentGatewayConfig.class);
    }

    private static final class CapturingSocket extends WebSocketAdapter {
        private final LinkedBlockingQueue<JSONObject> messages = new LinkedBlockingQueue<>();
        @Override public void onWebSocketText(String message) {
            messages.add(JSON.parseObject(message));
        }
        JSONObject next() throws Exception {
            JSONObject value = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull("timed out waiting for WebSocket frame", value);
            return value;
        }
    }

    private static final class Runtime implements GatewayRuntime {
        @Override public JSONArray targets() { return new JSONArray(); }
        @Override public JSONObject status(GatewayTarget target) {
            JSONObject value = accepted("OK");
            value.put("sessionId", "session-1");
            value.put("state", "READY");
            value.put("surface", "STARWEAVE");
            value.put("displayName", "Agent One");
            return value;
        }
        @Override public JSONObject send(GatewayTarget target, String message,
                                         List<Map<String, String>> files, String busyPolicy) {
            return accepted("PROMPT_ACCEPTED");
        }
        @Override public JSONObject cancel(GatewayTarget target) {
            return accepted("CANCEL_REQUESTED");
        }
        @Override public JSONObject newSession(GatewayTarget target) {
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
