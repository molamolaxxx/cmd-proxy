package com.mola.cmd.proxy.app.acp.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.util.Set;

/** Minimal MCP Streamable HTTP endpoint for cmd-proxy-owned action tools. */
public final class CmdProxyMcpHttpHandler implements HttpHandler {

    public static final String PATH = "/mcp";
    public static final String SERVER_NAME = "cmd-proxy-runtime";
    public static final String AUTH_SESSION_HEADER = "X-Cmd-Proxy-Auth-Session-Id";
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        InetAddress remote = exchange.getRemoteAddress() == null
                ? null : exchange.getRemoteAddress().getAddress();
        if (remote == null || !remote.isLoopbackAddress()) {
            send(exchange, 403, error(null, -32001, "Loopback access only"));
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            send(exchange, 405, error(null, -32600, "Only POST is supported"));
            return;
        }

        JsonObject request;
        try {
            byte[] body = readAll(exchange);
            request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RequestTooLargeException e) {
            send(exchange, 413, error(null, -32600, "Request body too large"));
            return;
        } catch (RuntimeException e) {
            send(exchange, 400, error(null, -32700, "Invalid JSON"));
            return;
        }

        JsonElement id = request.get("id");
        String method = string(request, "method");
        if ("notifications/initialized".equals(method)) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }

        JsonObject response;
        try {
            switch (method) {
                case "initialize":
                    response = success(id, initializeResult(request));
                    break;
                case "tools/list":
                    JsonObject list = new JsonObject();
                    list.add("tools", tools(ActionRuntimeRegistry.getInstance().availableTools(
                            exchange.getRequestHeaders().getFirst(AUTH_SESSION_HEADER))));
                    response = success(id, list);
                    break;
                case "tools/call":
                    response = success(id, callTool(exchange, request));
                    break;
                case "ping":
                    response = success(id, new JsonObject());
                    break;
                default:
                    response = error(id, -32601, "Method not found: " + method);
            }
        } catch (Exception e) {
            response = success(id, toolError(e.getMessage()));
        }
        send(exchange, 200, response);
    }

    private JsonObject initializeResult(JsonObject request) {
        JsonObject params = request.getAsJsonObject("params");
        String requested = params == null ? "" : string(params, "protocolVersion");
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", requested.isEmpty() ? PROTOCOL_VERSION : requested);
        JsonObject capabilities = new JsonObject();
        JsonObject toolCapabilities = new JsonObject();
        toolCapabilities.addProperty("listChanged", false);
        capabilities.add("tools", toolCapabilities);
        result.add("capabilities", capabilities);
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", SERVER_NAME);
        serverInfo.addProperty("version", "1.0.0");
        result.add("serverInfo", serverInfo);
        return result;
    }

    private JsonObject callTool(HttpExchange exchange, JsonObject request) throws Exception {
        JsonObject params = request.getAsJsonObject("params");
        if (params == null) return toolError("Missing params");
        String name = string(params, "name");
        if (!isActionTool(name)) return toolError("Unknown tool: " + name);
        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        String authSessionId = exchange.getRequestHeaders().getFirst(AUTH_SESSION_HEADER);
        String result = ActionRuntimeRegistry.getInstance()
                .execute(authSessionId, name, arguments);
        JsonObject payload = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", result == null ? "" : result);
        content.add(text);
        payload.add("content", content);
        payload.addProperty("isError", false);
        return payload;
    }

    public static JsonArray tools() {
        return tools(new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                "dispatch_subagent", "schedule_task", "manage_schedule", "talk_to")));
    }

    public static JsonArray tools(Set<String> availableTools) {
        JsonArray tools = new JsonArray();
        if (availableTools.contains("dispatch_subagent")) tools.add(tool("dispatch_subagent",
                "Dispatch one or more configured sub-agents in parallel and return aggregated results.",
                objectSchema("tasks", arrayOf(objectWithRequired(
                        new String[]{"agent", "title", "prompt"},
                        property("agent", "string"), property("title", "string"),
                        property("prompt", "string"))))));

        JsonObject scheduleTask = objectWithRequired(new String[]{"title", "prompt", "schedule"},
                property("title", "string"), property("prompt", "string"),
                objectProperty("schedule", objectWithRequired(new String[]{"type", "expr"},
                        property("type", "string"), property("expr", "string"))));
        JsonObject createSchema = objectSchema("tasks", arrayOf(scheduleTask));
        addProperty(createSchema, "groupName", stringSchema());
        if (availableTools.contains("schedule_task")) {
            tools.add(tool("schedule_task", "Create one or more scheduled tasks.", createSchema));
        }

        JsonObject manage = objectSchema("operation", stringSchema());
        addProperty(manage, "taskId", stringSchema());
        JsonObject updates = new JsonObject();
        updates.addProperty("type", "object");
        JsonObject updateProps = new JsonObject();
        updateProps.add("title", stringSchema());
        updateProps.add("prompt", stringSchema());
        updateProps.add("schedule", objectWithRequired(new String[]{"type", "expr"},
                property("type", "string"), property("expr", "string")));
        updates.add("properties", updateProps);
        addProperty(manage, "updates", updates);
        if (availableTools.contains("manage_schedule")) {
            tools.add(tool("manage_schedule", "List, cancel, or update scheduled tasks.", manage));
        }

        JsonObject talkTo = objectWithRequired(new String[]{"target", "content"},
                property("target", "string"), property("content", "string"));
        addProperty(talkTo, "_depth", schema("integer"));
        if (availableTools.contains("talk_to")) {
            tools.add(tool("talk_to", "Asynchronously send a message to a configured contact, Team member, or bound channel reply target.",
                    talkTo));
        }
        return tools;
    }

    private static boolean isActionTool(String name) {
        return "dispatch_subagent".equals(name) || "schedule_task".equals(name)
                || "manage_schedule".equals(name) || "talk_to".equals(name);
    }

    private static JsonObject tool(String name, String description, JsonObject schema) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        tool.add("inputSchema", schema);
        return tool;
    }

    private static JsonObject objectSchema(String requiredName, JsonObject requiredSchema) {
        return objectWithRequired(new String[]{requiredName},
                objectProperty(requiredName, requiredSchema));
    }

    private static JsonObject objectWithRequired(String[] required, JsonObject... properties) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        for (JsonObject property : properties) {
            for (java.util.Map.Entry<String, JsonElement> entry : property.entrySet()) {
                props.add(entry.getKey(), entry.getValue());
            }
        }
        schema.add("properties", props);
        JsonArray req = new JsonArray();
        for (String value : required) req.add(value);
        schema.add("required", req);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject property(String name, String type) {
        return objectProperty(name, schema(type));
    }

    private static JsonObject objectProperty(String name, JsonObject value) {
        JsonObject property = new JsonObject();
        property.add(name, value);
        return property;
    }

    private static JsonObject schema(String type) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", type);
        return schema;
    }

    private static JsonObject stringSchema() {
        return schema("string");
    }

    private static JsonObject arrayOf(JsonObject item) {
        JsonObject schema = schema("array");
        schema.add("items", item);
        schema.addProperty("minItems", 1);
        return schema;
    }

    private static void addProperty(JsonObject objectSchema, String name, JsonObject schema) {
        objectSchema.getAsJsonObject("properties").add(name, schema);
    }

    private static JsonObject toolError(String message) {
        JsonObject payload = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", message == null ? "Tool execution failed" : message);
        content.add(text);
        payload.add("content", content);
        payload.addProperty("isError", true);
        return payload;
    }

    private static JsonObject success(JsonElement id, JsonObject result) {
        JsonObject response = base(id);
        response.add("result", result);
        return response;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject response = base(id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        return response;
    }

    private static JsonObject base(JsonElement id) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id.deepCopy());
        return response;
    }

    private static String string(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString() : "";
    }

    private static byte[] readAll(HttpExchange exchange) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = exchange.getRequestBody().read(buffer)) >= 0) {
            if (out.size() + read > MAX_REQUEST_BYTES) {
                throw new RequestTooLargeException();
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void send(HttpExchange exchange, int status, JsonObject response) throws IOException {
        boolean sse = acceptsSse(exchange.getRequestHeaders());
        String body = response.toString();
        String encoded = sse ? "event: message\ndata: " + body + "\n\n" : body;
        byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",
                sse ? "text/event-stream; charset=utf-8" : "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static boolean acceptsSse(Headers headers) {
        String accept = headers.getFirst("Accept");
        return accept != null && accept.contains("text/event-stream")
                && !accept.contains("application/json");
    }

    private static final class RequestTooLargeException extends IOException {
    }
}
