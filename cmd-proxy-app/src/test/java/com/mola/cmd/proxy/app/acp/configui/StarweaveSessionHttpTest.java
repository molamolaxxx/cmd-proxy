package com.mola.cmd.proxy.app.acp.configui;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class StarweaveSessionHttpTest {
    private ConfigUiServer server;
    private String baseUrl;
    private int port;

    @Before
    public void startServer() throws Exception {
        server = new ConfigUiServer(0, () -> { }, ignored -> { });
        server.start();
        port = server.getBoundPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    @After
    public void stopServer() {
        if (server != null) server.stop();
    }

    @Test
    public void listsSessionsInVersionedEnvelopeWithoutRunningAcp() throws Exception {
        Response response = request("GET", "/api/starweave/v1/sessions", null, null);
        assertEquals(200, response.status);
        JSONObject body = JSON.parseObject(response.body);
        assertEquals(1, body.getIntValue("schemaVersion"));
        assertTrue(body.getBooleanValue("accepted"));
        assertEquals("OK", body.getString("code"));
        assertNotNull(body.getString("requestId"));
        assertTrue(body.getJSONObject("data").getJSONArray("sessions").isEmpty());
    }

    @Test
    public void rejectsCrossSiteBrowserRequestsBeforeDispatch() throws Exception {
        Response response = rawCrossSiteGet("/api/starweave/v1/sessions");
        assertEquals(403, response.status);
        assertEquals("ORIGIN_REJECTED", JSON.parseObject(response.body).getString("code"));
    }

    @Test
    public void mapsUnavailableRuntimeToStableCommandEnvelope() throws Exception {
        Response response = request("POST", "/api/starweave/v1/sessions/open",
                "{\"requestId\":\"http-test-1\",\"robotName\":\"Robot\"}", null);
        assertEquals(503, response.status);
        JSONObject body = JSON.parseObject(response.body);
        assertFalse(body.getBooleanValue("accepted"));
        assertEquals("SERVICE_UNAVAILABLE", body.getString("code"));
        assertEquals("http-test-1", body.getString("requestId"));
    }

    @Test
    public void validatesSseGenerationBeforeOpeningStream() throws Exception {
        Response response = request("GET",
                "/api/starweave/v1/sessions/stream?groupId=g&sessionId=s&generation=0",
                null, null);
        assertEquals(400, response.status);
        assertEquals("INVALID_GENERATION", JSON.parseObject(response.body).getString("code"));
    }

    private Response request(String method, String path, String body,
                             String[][] headers) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(5_000);
        if (headers != null) {
            for (String[] header : headers) connection.setRequestProperty(header[0], header[1]);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (input != null) {
            try (InputStream source = input) {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = source.read(buffer)) >= 0) output.write(buffer, 0, read);
            }
        }
        connection.disconnect();
        return new Response(status,
                new String(output.toByteArray(), StandardCharsets.UTF_8));
    }

    private Response rawCrossSiteGet(String path) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream request = socket.getOutputStream();
            String value = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Origin: https://attacker.invalid\r\n"
                    + "Sec-Fetch-Site: cross-site\r\n"
                    + "Connection: close\r\n\r\n";
            request.write(value.getBytes(StandardCharsets.US_ASCII));
            request.flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            InputStream input = socket.getInputStream();
            while ((read = input.read(buffer)) >= 0) response.write(buffer, 0, read);
            String wire = new String(response.toByteArray(), StandardCharsets.UTF_8);
            int headerEnd = wire.indexOf("\r\n\r\n");
            String statusLine = wire.substring(0, wire.indexOf("\r\n"));
            int status = Integer.parseInt(statusLine.split(" ")[1]);
            return new Response(status, headerEnd < 0 ? "" : wire.substring(headerEnd + 4));
        }
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
