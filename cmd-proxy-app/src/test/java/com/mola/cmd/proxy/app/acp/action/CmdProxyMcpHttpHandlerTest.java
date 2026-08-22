package com.mola.cmd.proxy.app.acp.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.Assert.*;

public class CmdProxyMcpHttpHandlerTest {
    private HttpServer server;
    private String endpoint;

    @Before
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(CmdProxyMcpHttpHandler.PATH, new CmdProxyMcpHttpHandler());
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort()
                + CmdProxyMcpHttpHandler.PATH;
    }

    @After
    public void stop() {
        if (server != null) server.stop(0);
        ActionRuntimeRegistry.getInstance().unregister("test-session");
        ActionRuntimeRegistry.getInstance().unregister("list-session");
    }

    @Test
    public void listsOnlyToolsAvailableToAuthSession() throws Exception {
        ActionRuntimeRegistry.getInstance().register("list-session", (name, arguments) -> "ok",
                () -> new LinkedHashSet<>(Arrays.asList("schedule_task", "manage_schedule")));
        JsonObject response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                "application/json", "list-session");
        JsonArray tools = response.getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(2, tools.size());
        assertEquals("schedule_task", tools.get(0).getAsJsonObject().get("name").getAsString());
        assertTrue(tools.get(0).getAsJsonObject().has("inputSchema"));
    }

    @Test
    public void routesToolCallByAuthSessionHeader() throws Exception {
        ActionRuntimeRegistry.getInstance().register("test-session",
                (name, arguments) -> name + ":" + arguments.get("target").getAsString(),
                () -> java.util.Collections.singleton("talk_to"));
        JsonObject response = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"talk_to\",\"arguments\":{\"target\":\"Agent B\",\"content\":\"hi\"}}}",
                "application/json", "test-session");
        JsonObject result = response.getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean());
        assertEquals("talk_to:Agent B", result.getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString());
    }

    @Test
    public void missingAuthContextReturnsToolError() throws Exception {
        JsonObject response = post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"talk_to\",\"arguments\":{}}}",
                "application/json", null);
        assertTrue(response.getAsJsonObject("result").get("isError").getAsBoolean());
    }

    @Test
    public void supportsSseResponseEnvelope() throws Exception {
        HttpURLConnection connection = open("text/event-stream", null);
        write(connection, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}");
        String body = read(connection.getInputStream());
        assertEquals("text/event-stream; charset=utf-8", connection.getContentType());
        assertTrue(body.startsWith("event: message\ndata: "));
    }

    @Test
    public void rejectsOversizedRequestBody() throws Exception {
        HttpURLConnection connection = open("application/json", null);
        char[] payload = new char[1024 * 1024 + 1];
        java.util.Arrays.fill(payload, 'x');
        write(connection, new String(payload));
        assertEquals(413, connection.getResponseCode());
        JsonObject response = JsonParser.parseString(read(connection.getErrorStream()))
                .getAsJsonObject();
        assertEquals(-32600, response.getAsJsonObject("error").get("code").getAsInt());
    }

    private JsonObject post(String body, String accept, String authSessionId) throws Exception {
        HttpURLConnection connection = open(accept, authSessionId);
        write(connection, body);
        return JsonParser.parseString(read(connection.getInputStream())).getAsJsonObject();
    }

    private HttpURLConnection open(String accept, String authSessionId) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", accept);
        if (authSessionId != null) {
            connection.setRequestProperty(CmdProxyMcpHttpHandler.AUTH_SESSION_HEADER, authSessionId);
        }
        return connection;
    }

    private static void write(HttpURLConnection connection, String body) throws Exception {
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(InputStream input) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
