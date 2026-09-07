package com.mola.cmd.proxy.app.acp.task.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.task.model.TaskException;
import com.mola.cmd.proxy.app.acp.task.service.TaskService;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ExternalTaskApiServiceTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void authenticatesMergesOverridesAndDeduplicatesPersistently() throws Exception {
        Path root = temporary.newFolder("external-task-api").toPath();
        Path config = writeConfig(root, endpoints(
                endpoint("orders", "orders-auth-code-123456", true,
                        "默认任务", "默认内容")));
        try (TaskService tasks = new TaskService(root.resolve("tasks"), "instance-1")) {
            ExternalTaskApiService service = new ExternalTaskApiService(config, () -> tasks);
            JSONObject body = new JSONObject(true);
            body.put("name", "订单任务");
            body.put("content", "来自业务系统的内容");

            JSONObject first = service.create("Bearer orders-auth-code-123456",
                    "order-1001", body);
            JSONObject replay = service.create("Bearer orders-auth-code-123456",
                    "order-1001", body);

            assertEquals(first.getJSONObject("task").getString("id"),
                    replay.getJSONObject("task").getString("id"));
            assertEquals("订单任务", first.getJSONObject("task").getString("name"));
            assertEquals("来自业务系统的内容",
                    first.getJSONObject("task").getString("contentMarkdown"));
            assertEquals(1L, tasks.list(new JSONObject(true)).getLongValue("total"));
        }
        try (TaskService reopened = new TaskService(root.resolve("tasks"), "instance-1")) {
            ExternalTaskApiService service = new ExternalTaskApiService(config, () -> reopened);
            JSONObject body = new JSONObject(true);
            body.put("name", "订单任务");
            body.put("content", "来自业务系统的内容");
            service.create("Bearer orders-auth-code-123456", "order-1001", body);
            assertEquals(1L, reopened.list(new JSONObject(true)).getLongValue("total"));
        }
    }

    @Test
    public void usesConfiguredDefaultsWhenRequestOmitsTaskFields() throws Exception {
        Path root = temporary.newFolder("external-task-defaults").toPath();
        Path config = writeConfig(root, endpoints(
                endpoint("alerts", "alerts-auth-code-123456", true,
                        "默认告警任务", "检查告警来源")));
        try (TaskService tasks = new TaskService(root.resolve("tasks"), "instance-1")) {
            JSONObject result = new ExternalTaskApiService(config, () -> tasks).create(
                    "Bearer alerts-auth-code-123456", "alert-1", new JSONObject(true));
            assertEquals("默认告警任务", result.getJSONObject("task").getString("name"));
            assertEquals("检查告警来源",
                    result.getJSONObject("task").getString("contentMarkdown"));
        }
    }

    @Test
    public void scopesIdempotencyKeyByEndpointAndRejectsChangedPayload() throws Exception {
        Path root = temporary.newFolder("external-task-scope").toPath();
        Path config = writeConfig(root, endpoints(
                endpoint("one", "endpoint-one-auth-123456", true, "一", ""),
                endpoint("two", "endpoint-two-auth-123456", true, "二", "")));
        try (TaskService tasks = new TaskService(root.resolve("tasks"), "instance-1")) {
            ExternalTaskApiService service = new ExternalTaskApiService(config, () -> tasks);
            service.create("Bearer endpoint-one-auth-123456", "same-key",
                    request("请求一", "内容一"));
            service.create("Bearer endpoint-two-auth-123456", "same-key",
                    request("请求二", "内容二"));
            assertEquals(2L, tasks.list(new JSONObject(true)).getLongValue("total"));

            try {
                service.create("Bearer endpoint-one-auth-123456", "same-key",
                        request("发生变化", "内容一"));
            } catch (TaskException expected) {
                assertEquals("IDEMPOTENCY_CONFLICT", expected.getCode());
                assertEquals(409, expected.getHttpStatus());
                return;
            }
            throw new AssertionError("expected idempotency conflict");
        }
    }

    @Test
    public void rejectsInvalidAuthenticationDisabledEndpointAndTargetOverride() throws Exception {
        Path root = temporary.newFolder("external-task-rejections").toPath();
        Path config = writeConfig(root, endpoints(
                endpoint("disabled", "disabled-auth-code-123456", false,
                        "默认任务", ""),
                endpoint("enabled", "enabled-auth-code-123456", true,
                        "默认任务", "")));
        try (TaskService tasks = new TaskService(root.resolve("tasks"), "instance-1")) {
            ExternalTaskApiService service = new ExternalTaskApiService(config, () -> tasks);
            assertFailure("INVALID_AUTH_CODE", 401, () -> service.create(
                    "Bearer wrong-auth-code-123456", "key", new JSONObject(true)));
            assertFailure("ENDPOINT_DISABLED", 403, () -> service.create(
                    "Bearer disabled-auth-code-123456", "key", new JSONObject(true)));
            JSONObject override = new JSONObject(true);
            override.put("name", "任务");
            override.put("target", new JSONObject(true));
            assertFailure("INVALID_ARGUMENT", 400, () -> service.create(
                    "Bearer enabled-auth-code-123456", "key-2", override));
            assertFalse(tasks.list(new JSONObject(true)).getJSONArray("items").size() > 0);
        }
    }

    private static JSONObject request(String name, String content) {
        JSONObject request = new JSONObject(true);
        request.put("name", name);
        request.put("content", content);
        return request;
    }

    private static JSONObject endpoint(String id, String authCode, boolean enabled,
                                       String defaultName, String defaultContent) {
        JSONObject endpoint = new JSONObject(true);
        endpoint.put("id", id);
        endpoint.put("name", id);
        endpoint.put("enabled", enabled);
        endpoint.put("authCode", authCode);
        endpoint.put("defaultTaskName", defaultName);
        endpoint.put("defaultTaskContent", defaultContent);
        JSONObject target = new JSONObject(true);
        target.put("type", "AGENT");
        target.put("instanceId", "instance-1");
        target.put("agentId", "acp-agent");
        endpoint.put("target", target);
        return endpoint;
    }

    private static JSONArray endpoints(JSONObject... endpoints) {
        JSONArray values = new JSONArray();
        for (JSONObject endpoint : endpoints) values.add(endpoint);
        return values;
    }

    private static Path writeConfig(Path root, JSONArray endpoints) throws Exception {
        JSONObject config = new JSONObject(true);
        config.put("externalTaskApis", endpoints);
        Path path = root.resolve("acpConfig.json");
        Files.write(path, JSON.toJSONString(config).getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static void assertFailure(String code, int status, CheckedRunnable action)
            throws Exception {
        try {
            action.run();
        } catch (TaskException expected) {
            assertEquals(code, expected.getCode());
            assertEquals(status, expected.getHttpStatus());
            return;
        }
        throw new AssertionError("expected failure " + code);
    }

    private interface CheckedRunnable { void run() throws Exception; }
}
