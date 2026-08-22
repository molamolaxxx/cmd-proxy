package com.mola.cmd.proxy.app.acp.action;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.mcpauth.McpAuthManager;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.Assert.*;

public class CmdProxyControlServerTest {
    private CmdProxyControlServer server;
    private String authSessionId;

    @After
    public void cleanup() {
        if (authSessionId != null) {
            ActionRuntimeRegistry.getInstance().unregister(authSessionId);
            McpAuthManager.getInstance().removeSession(authSessionId);
        }
        if (server != null) server.close();
    }

    @Test
    public void startsOnLoopbackAndServesMcpWithoutConfigUi() throws Exception {
        server = new CmdProxyControlServer();
        server.start();
        assertTrue(server.getBaseUrl().startsWith("http://127.0.0.1:"));
        assertEquals(server.getBaseUrl(), McpAuthManager.getInstance().getBaseUrl());

        authSessionId = McpAuthManager.getInstance().createSession("control-test");
        ActionRuntimeRegistry.getInstance().register(authSessionId,
                (name, arguments) -> "ok", () -> Collections.singleton("talk_to"));
        JsonObject response = post(server.getBaseUrl() + CmdProxyMcpHttpHandler.PATH,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                authSessionId);
        assertEquals(1, response.getAsJsonObject("result").getAsJsonArray("tools").size());
    }

    @Test
    public void servesMcpAuthorizationRuntimeEndpoints() throws Exception {
        server = new CmdProxyControlServer();
        server.start();
        authSessionId = McpAuthManager.getInstance().createSession("auth-test");
        JsonObject response = post(server.getBaseUrl()
                        + "/api/mcp-auth/v1/servers/register",
                "{\"authSessionId\":\"" + authSessionId
                        + "\",\"serverId\":\"server-1\",\"name\":\"Server\",\"tools\":[]}",
                null);
        assertTrue(response.get("success").getAsBoolean());
    }

    private static JsonObject post(String endpoint, String body, String sessionId)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        if (sessionId != null) {
            connection.setRequestProperty(CmdProxyMcpHttpHandler.AUTH_SESSION_HEADER, sessionId);
        }
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream in = connection.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            return JsonParser.parseString(new String(out.toByteArray(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
