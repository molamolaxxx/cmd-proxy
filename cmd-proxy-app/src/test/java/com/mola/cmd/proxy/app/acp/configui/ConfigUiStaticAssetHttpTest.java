package com.mola.cmd.proxy.app.acp.configui;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConfigUiStaticAssetHttpTest {
    private ConfigUiServer server;
    private String baseUrl;

    @Before
    public void startServer() throws Exception {
        server = new ConfigUiServer(0, () -> { }, ignored -> { });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getBoundPort();
    }

    @After
    public void stopServer() {
        if (server != null) server.stop();
    }

    @Test
    public void servesBundledMaterialIconsFontWithImmutableCaching() throws Exception {
        HttpURLConnection connection = open("/assets/MaterialIcons-Regular.woff2");

        assertEquals(200, connection.getResponseCode());
        assertEquals("font/woff2", connection.getHeaderField("Content-Type"));
        assertEquals("nosniff", connection.getHeaderField("X-Content-Type-Options"));
        assertEquals("public, max-age=31536000, immutable",
                connection.getHeaderField("Cache-Control"));
        byte[] body = readAll(connection.getInputStream());
        assertTrue(body.length > 100_000);
        assertArrayEquals("wOF2".getBytes(StandardCharsets.US_ASCII),
                new byte[]{body[0], body[1], body[2], body[3]});
        connection.disconnect();
    }

    @Test
    public void rejectsUnknownStaticAssets() throws Exception {
        HttpURLConnection connection = open("/assets/unknown.woff2");

        assertEquals(404, connection.getResponseCode());
        connection.disconnect();
    }

    private HttpURLConnection open(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(5_000);
        return connection;
    }

    private byte[] readAll(InputStream input) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = source.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }
}
