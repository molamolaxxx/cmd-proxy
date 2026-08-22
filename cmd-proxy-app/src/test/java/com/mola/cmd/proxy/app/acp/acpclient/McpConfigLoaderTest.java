package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class McpConfigLoaderTest {

    @Test
    public void appendsRuntimeAuthContextWithoutExposingItToToolArguments() {
        // Exercise the public merge path with a temporary JSON config.
        try {
            java.nio.file.Path file = java.nio.file.Files.createTempFile("mcp-auth", ".json");
            java.nio.file.Files.write(file, ("{\"mcpServers\":{\"secured\":{" +
                    "\"url\":\"https://example.test/mcp\"}}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            JsonArray servers = McpConfigLoader.loadFromPaths(
                    java.util.Collections.singletonList(file), "auth-123",
                    "http://127.0.0.1:10528");
            JsonArray headers = servers.get(0).getAsJsonObject().getAsJsonArray("headers");
            assertEquals("auth-123", namedValue(headers,
                    "X-Cmd-Proxy-Auth-Session-Id"));
            assertEquals("http://127.0.0.1:10528", namedValue(headers,
                    "X-Cmd-Proxy-Auth-Base-Url"));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String namedValue(JsonArray values, String name) {
        for (int i = 0; i < values.size(); i++) {
            JsonObject value = values.get(i).getAsJsonObject();
            if (name.equals(value.get("name").getAsString())) {
                return value.get("value").getAsString();
            }
        }
        return null;
    }

    @Test
    public void httpServerWithoutConfiguredHeadersStillIncludesEmptyHeadersArray() {
        JsonObject configured = new JsonObject();
        configured.addProperty("type", "streamable-http");
        configured.addProperty("url", "http://127.0.0.1:8080/mcp/example");

        JsonObject converted = McpConfigLoader.convertToAcpFormat("example", configured);

        assertEquals("example", converted.get("name").getAsString());
        assertEquals("http", converted.get("type").getAsString());
        assertEquals("http://127.0.0.1:8080/mcp/example", converted.get("url").getAsString());
        assertTrue(converted.has("headers"));
        assertTrue(converted.get("headers").isJsonArray());
        assertEquals(0, converted.getAsJsonArray("headers").size());
    }

    @Test
    public void httpServerConvertsConfiguredHeadersToAcpArray() {
        JsonObject configured = new JsonObject();
        configured.addProperty("url", "https://example.test/mcp");
        JsonObject headers = new JsonObject();
        headers.addProperty("Authorization", "Bearer token");
        configured.add("headers", headers);

        JsonObject converted = McpConfigLoader.convertToAcpFormat("secured", configured);

        JsonArray convertedHeaders = converted.getAsJsonArray("headers");
        assertEquals(1, convertedHeaders.size());
        assertEquals("Authorization",
                convertedHeaders.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("Bearer token",
                convertedHeaders.get(0).getAsJsonObject().get("value").getAsString());
    }

    @Test
    public void claudeScopesUseLocalThenProjectThenUserAndInjectAuthHeaders() throws Exception {
        java.nio.file.Path home = java.nio.file.Files.createTempDirectory("claude-mcp-home");
        java.nio.file.Path workspace = java.nio.file.Files.createDirectories(home.resolve("work"));
        java.nio.file.Path global = home.resolve(".claude.json");
        java.nio.file.Path project = workspace.resolve(".mcp.json");
        String workspaceKey = workspace.toString().replace("\\", "\\\\");
        java.nio.file.Files.write(global, ("{\"mcpServers\":{" +
                "\"shared\":{\"url\":\"https://user/shared\"}," +
                "\"user-only\":{\"url\":\"https://user/only\"}}," +
                "\"projects\":{\"" + workspaceKey + "\":{\"mcpServers\":{" +
                "\"shared\":{\"url\":\"https://local/shared\"}," +
                "\"local-only\":{\"url\":\"https://local/only\"}}}}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.write(project, ("{\"mcpServers\":{" +
                "\"shared\":{\"url\":\"https://project/shared\"}," +
                "\"project-only\":{\"url\":\"https://project/only\"}}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        JsonArray servers = McpConfigLoader.loadFromPaths(
                java.util.Arrays.asList(global, project), "claude-auth",
                "http://127.0.0.1:10528", workspace.toString());

        assertEquals("https://local/shared", server(servers, "shared").get("url").getAsString());
        assertEquals("https://project/only", server(servers, "project-only").get("url").getAsString());
        assertEquals("https://user/only", server(servers, "user-only").get("url").getAsString());
        assertEquals("claude-auth", namedValue(server(servers, "shared")
                .getAsJsonArray("headers"), "X-Cmd-Proxy-Auth-Session-Id"));
        assertEquals(java.util.Arrays.asList("shared", "local-only", "project-only", "user-only"),
                McpConfigLoader.loadServerNames(
                        java.util.Arrays.asList(global, project), workspace.toString()));
    }

    private static JsonObject server(JsonArray servers, String name) {
        for (int i = 0; i < servers.size(); i++) {
            JsonObject server = servers.get(i).getAsJsonObject();
            if (name.equals(server.get("name").getAsString())) return server;
        }
        throw new AssertionError("Missing MCP server: " + name);
    }
}
