package com.mola.cmd.proxy.app.acp.configui;

import com.mola.cmd.proxy.app.acp.schedule.ScheduleAdminApiBridge;
import com.mola.cmd.proxy.app.acp.schedule.ScheduleTaskManager;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleConfig;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ScheduleAdminHttpTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void servesScheduleListStatsAndMethodGuard() throws Exception {
        ScheduleTaskManager manager = new ScheduleTaskManager(
                temporaryFolder.newFolder("http-schedules").toPath());
        manager.createTask(ScheduleOwnerKey.main("assistant"), "每日早报", "生成早报",
                new ScheduleConfig("cron", "0 8 * * *"));
        ScheduleAdminApiBridge.install(manager);
        ConfigUiServer server = new ConfigUiServer(0, () -> {}, ignored -> {});
        try {
            server.start();
            int port = server.getBoundPort();
            Response list = request(port, "GET", "/api/schedules/v1/tasks?page=1&pageSize=10");
            assertEquals(200, list.status);
            assertTrue(list.body.contains("\"title\":\"每日早报\""));
            assertTrue(!list.body.contains("authPrincipalContext"));
            Response stats = request(port, "GET", "/api/schedules/v1/stats");
            assertEquals(200, stats.status);
            assertTrue(stats.body.contains("\"total\":1"));
            assertEquals(405, request(port, "POST", "/api/schedules/v1/tasks").status);
        } finally {
            server.stop();
            ScheduleAdminApiBridge.clear(manager);
        }
    }

    private static Response request(int port, String method, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod(method);
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (input != null) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        }
        connection.disconnect();
        return new Response(status, new String(output.toByteArray(), StandardCharsets.UTF_8));
    }

    private static final class Response {
        private final int status;
        private final String body;
        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
