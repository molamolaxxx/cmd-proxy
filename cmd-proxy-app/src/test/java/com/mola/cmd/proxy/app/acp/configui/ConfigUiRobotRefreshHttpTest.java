package com.mola.cmd.proxy.app.acp.configui;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConfigUiRobotRefreshHttpTest {
    private ConfigUiServer server;

    @After
    public void stopServer() {
        if (server != null) server.stop();
    }

    @Test
    public void forwardsPreviousAndCurrentRobotNames() throws Exception {
        AtomicReference<String> names = new AtomicReference<>();
        server = new ConfigUiServer(0, () -> { },
                (previousName, name) -> names.set(previousName + "->" + name));
        server.start();

        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getBoundPort() + "/api/refresh-robot")
                .openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        byte[] body = "{\"previousName\":\"before\",\"name\":\"after\"}"
                .getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }

        assertEquals(200, connection.getResponseCode());
        assertEquals("before->after", names.get());
        connection.disconnect();
    }

    @Test
    public void exposesRunningRefreshAcrossIndependentPageRequests() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService client = Executors.newSingleThreadExecutor();
        server = new ConfigUiServer(0, () -> { }, (previousName, name) -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("refresh release timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("refresh interrupted", e);
            }
        });
        server.start();

        try {
            Future<Integer> refresh = client.submit(() -> postRobotRefresh("before", "after"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            String running = get("/api/item-refresh-status");
            assertTrue(running.contains("\"after\":"));

            release.countDown();
            assertEquals(Integer.valueOf(200), refresh.get(2, TimeUnit.SECONDS));
            assertFalse(get("/api/item-refresh-status").contains("\"after\":"));
        } finally {
            release.countDown();
            client.shutdownNow();
        }
    }

    private int postRobotRefresh(String previousName, String name) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getBoundPort() + "/api/refresh-robot")
                .openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        byte[] body = ("{\"previousName\":\"" + previousName + "\",\"name\":\""
                + name + "\"}").getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }
        int status = connection.getResponseCode();
        connection.disconnect();
        return status;
    }

    private String get(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getBoundPort() + path).openConnection();
        connection.setRequestMethod("GET");
        assertEquals(200, connection.getResponseCode());
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }
}
