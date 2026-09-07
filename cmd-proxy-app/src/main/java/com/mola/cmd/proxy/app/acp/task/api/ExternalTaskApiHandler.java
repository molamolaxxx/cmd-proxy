package com.mola.cmd.proxy.app.acp.task.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.mola.cmd.proxy.app.acp.task.model.TaskException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Public server-to-server entrypoint for idempotent task creation. */
public final class ExternalTaskApiHandler implements HttpHandler {
    public static final String PREFIX = "/api/external/v1/tasks";
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final ExternalTaskApiService service;

    public ExternalTaskApiHandler(ExternalTaskApiService service) {
        this.service = service;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!PREFIX.equals(exchange.getRequestURI().getPath())) {
                throw new TaskException("NOT_FOUND", "Route not found", 404);
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                throw new TaskException("METHOD_NOT_ALLOWED", "Method not allowed", 405);
            }
            JSONObject body = parseBody(exchange.getRequestBody());
            JSONObject result = service.create(
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"), body);
            JSONObject data = new JSONObject(true);
            JSONObject task = result.getJSONObject("task");
            data.put("taskId", task == null ? null : task.getString("id"));
            data.put("name", task == null ? null : task.getString("name"));
            data.put("status", task == null ? null : task.getString("status"));
            data.put("revision", task == null ? null : task.getLong("revision"));
            data.put("contentVersion", task == null ? null
                    : task.getLong("contentVersion"));
            send(exchange, 201, envelope(true, "TASK_CREATED",
                    "Task accepted", data));
        } catch (TaskException e) {
            send(exchange, e.getHttpStatus(), envelope(false, e.getCode(),
                    e.getMessage(), e.getData()));
        } catch (com.alibaba.fastjson.JSONException | IllegalArgumentException e) {
            send(exchange, 400, envelope(false, "INVALID_ARGUMENT",
                    "Invalid JSON request", new JSONObject(true)));
        } catch (RuntimeException e) {
            send(exchange, 503, envelope(false, "TASK_SERVICE_UNAVAILABLE",
                    "Task service is unavailable", new JSONObject(true)));
        } finally {
            exchange.close();
        }
    }

    static JSONObject envelope(boolean accepted, String code, String message,
                               JSONObject data) {
        JSONObject value = new JSONObject(true);
        value.put("accepted", accepted);
        value.put("code", code);
        value.put("message", message);
        value.put("data", data == null ? new JSONObject(true) : data);
        return value;
    }

    private static JSONObject parseBody(InputStream input) throws IOException {
        try (InputStream body = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = body.read(buffer)) != -1) {
                if (bytes.size() + read > MAX_BODY_BYTES) {
                    throw new TaskException("RESOURCE_LIMIT", "Request body is too large", 413);
                }
                bytes.write(buffer, 0, read);
            }
            if (bytes.size() == 0) return new JSONObject(true);
            JSONObject parsed = JSON.parseObject(new String(bytes.toByteArray(),
                    StandardCharsets.UTF_8));
            if (parsed == null) throw new IllegalArgumentException("JSON object required");
            return parsed;
        }
    }

    private static void send(HttpExchange exchange, int status, JSONObject value)
            throws IOException {
        byte[] data = JSON.toJSONString(value, SerializerFeature.WriteMapNullValue)
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, data.length);
        try (java.io.OutputStream output = exchange.getResponseBody()) {
            output.write(data);
        }
    }
}
