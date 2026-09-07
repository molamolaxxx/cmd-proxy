package com.mola.cmd.proxy.app.acp.task.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.task.service.TaskService;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExternalTaskApiHandlerTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private TaskService tasks;
    private HttpServer server;
    private String endpoint;

    @Before
    public void start() throws Exception {
        Path root = temporary.newFolder("external-task-http").toPath();
        Path config = root.resolve("acpConfig.json");
        Files.write(config, ("{\"externalTaskApis\":[{"
                + "\"id\":\"orders\",\"name\":\"订单\",\"enabled\":true,"
                + "\"authCode\":\"orders-http-auth-123456\","
                + "\"defaultTaskName\":\"默认订单任务\","
                + "\"target\":{\"type\":\"AGENT\","
                + "\"instanceId\":\"instance-1\",\"agentId\":\"acp-agent\"}}]}"
        ).getBytes(StandardCharsets.UTF_8));
        tasks = new TaskService(root.resolve("tasks"), "instance-1");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ExternalTaskApiHandler.PREFIX,
                new ExternalTaskApiHandler(new ExternalTaskApiService(config, () -> tasks)));
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort()
                + ExternalTaskApiHandler.PREFIX;
    }

    @After
    public void stop() {
        if (server != null) server.stop(0);
        if (tasks != null) tasks.close();
    }

    @Test
    public void acceptsHeaderAuthenticatedRequestAndReturnsStableEnvelope() throws Exception {
        HttpURLConnection connection = request("POST", "orders-http-auth-123456",
                "order-1", "{\"name\":\"订单一\",\"content\":\"检查订单\"}");

        assertEquals(201, connection.getResponseCode());
        JSONObject response = JSON.parseObject(read(connection.getInputStream()));
        assertTrue(response.getBooleanValue("accepted"));
        assertEquals("TASK_CREATED", response.getString("code"));
        assertEquals("订单一", response.getJSONObject("data").getString("name"));
        assertEquals("no-store", connection.getHeaderField("Cache-Control"));
    }

    @Test
    public void returnsStableAuthenticationAndIdempotencyErrors() throws Exception {
        HttpURLConnection unauthorized = request("POST", null, "order-2", "{}");
        assertEquals(401, unauthorized.getResponseCode());
        assertEquals("INVALID_AUTH_CODE", JSON.parseObject(read(
                unauthorized.getErrorStream())).getString("code"));

        assertEquals(201, request("POST", "orders-http-auth-123456",
                "order-3", "{\"name\":\"A\"}").getResponseCode());
        HttpURLConnection conflict = request("POST", "orders-http-auth-123456",
                "order-3", "{\"name\":\"B\"}");
        assertEquals(409, conflict.getResponseCode());
        assertEquals("IDEMPOTENCY_CONFLICT", JSON.parseObject(read(
                conflict.getErrorStream())).getString("code"));
    }

    private HttpURLConnection request(String method, String authCode,
                                      String idempotencyKey, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestProperty("Content-Type", "application/json");
        if (authCode != null) connection.setRequestProperty(
                "Authorization", "Bearer " + authCode);
        if (idempotencyKey != null) connection.setRequestProperty(
                "Idempotency-Key", idempotencyKey);
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return connection;
    }

    private static String read(InputStream input) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream output =
                new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = source.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
