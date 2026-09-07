package com.mola.cmd.proxy.app.acp.task.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.task.model.TaskException;
import com.mola.cmd.proxy.app.acp.task.service.TaskService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/** Authenticates a configured external task endpoint and delegates to TaskService. */
public final class ExternalTaskApiService {
    private static final Set<String> REQUEST_FIELDS =
            new HashSet<>(Arrays.asList("name", "content"));

    private final Path configPath;
    private final Supplier<TaskService> taskServiceSupplier;

    public ExternalTaskApiService(Path configPath, Supplier<TaskService> taskServiceSupplier) {
        this.configPath = configPath;
        this.taskServiceSupplier = taskServiceSupplier;
    }

    public JSONObject create(String authorization, String idempotencyKey, JSONObject body) {
        String authCode = bearer(authorization);
        JSONObject endpoint = authenticate(authCode);
        if (!endpoint.getBooleanValue("enabled")) {
            throw new TaskException("ENDPOINT_DISABLED",
                    "External task endpoint is disabled", 403);
        }
        String key = required(idempotencyKey, "Idempotency-Key", 512);
        JSONObject request = body == null ? new JSONObject(true) : body;
        for (String field : request.keySet()) {
            if (!REQUEST_FIELDS.contains(field)) {
                throw new TaskException("INVALID_ARGUMENT",
                        "Unsupported request field: " + field, 400);
            }
        }

        JSONObject target = endpoint.getJSONObject("target");
        if (target == null) {
            throw new TaskException("TASK_TARGET_UNAVAILABLE",
                    "External task endpoint has no target", 422);
        }

        String name = preferred(request.getString("name"),
                endpoint.getString("defaultTaskName"));
        String content = preferred(request.getString("content"),
                endpoint.getString("defaultTaskContent"));
        JSONObject taskRequest = new JSONObject(true);
        taskRequest.put("requestId", scopedRequestId(endpoint.getString("id"), key));
        taskRequest.put("name", required(name, "name", 200));
        taskRequest.put("contentMarkdown", content == null ? "" : content);
        taskRequest.put("attachmentIds", new JSONArray());
        taskRequest.put("target", new JSONObject(target));
        taskRequest.put("creatorName", "External API · "
                + required(endpoint.getString("id"), "endpoint.id", 120));
        return taskServiceSupplier.get().create(taskRequest);
    }

    private JSONObject authenticate(String authCode) {
        JSONObject root;
        try {
            if (!Files.exists(configPath)) throw invalidAuth();
            root = JSON.parseObject(new String(Files.readAllBytes(configPath),
                    StandardCharsets.UTF_8));
        } catch (TaskException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new TaskException("TASK_SERVICE_UNAVAILABLE",
                    "External task configuration is unavailable", 503);
        }
        JSONArray endpoints = root == null ? null : root.getJSONArray("externalTaskApis");
        if (endpoints == null) throw invalidAuth();
        byte[] supplied = authCode.getBytes(StandardCharsets.UTF_8);
        JSONObject matched = null;
        for (int i = 0; i < endpoints.size(); i++) {
            JSONObject endpoint = endpoints.getJSONObject(i);
            String configured = endpoint == null ? null : endpoint.getString("authCode");
            if (configured == null) continue;
            if (MessageDigest.isEqual(supplied,
                    configured.getBytes(StandardCharsets.UTF_8))) {
                matched = endpoint;
            }
        }
        if (matched == null) throw invalidAuth();
        return matched;
    }

    private static String bearer(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0,
                "Bearer ", 0, 7)) throw invalidAuth();
        String value = authorization.substring(7).trim();
        if (value.isEmpty() || value.length() > 512) throw invalidAuth();
        return value;
    }

    private static TaskException invalidAuth() {
        return new TaskException("INVALID_AUTH_CODE", "Invalid authentication code", 401);
    }

    private static String preferred(String supplied, String fallback) {
        return supplied != null && !supplied.trim().isEmpty() ? supplied : fallback;
    }

    private static String required(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new TaskException("INVALID_ARGUMENT", field + " is required", 400);
        }
        if (normalized.length() > maxLength) {
            throw new TaskException("INVALID_ARGUMENT", field + " is too long", 400);
        }
        return normalized;
    }

    private static String scopedRequestId(String endpointId, String idempotencyKey) {
        return "external:" + sha256(endpointId) + ":" + sha256(idempotencyKey);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(digest.length * 2);
            for (byte item : digest) text.append(String.format("%02x", item & 0xff));
            return text.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
