package com.mola.cmd.proxy.app.mcpclient;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 基于 HTTP (Streamable HTTP) 的 MCP Client 实现。
 * <p>
 * 通信方式: 向 MCP Server 的 HTTP endpoint 发送 JSON-RPC 请求，
 * 支持普通 JSON 响应和 SSE (text/event-stream) 响应两种模式。
 * <p>
 * 配置示例:
 * <pre>
 * "confluence": {
 *   "url": "https://example.com/mcp/confluence/",
 *   "headers": { "Authorization": "Token xxx" },
 *   "disabled": false,
 *   "autoApprove": ["tool1", "tool2"]
 * }
 * </pre>
 */
public class HttpMcpClient extends McpClient {

    private static final Logger logger = LoggerFactory.getLogger(HttpMcpClient.class);
    private static final int CONNECT_TIMEOUT = 30_000;
    private static final int READ_TIMEOUT = 120_000;

    /** MCP Server 的 HTTP endpoint */
    private final String serverUrl;
    /** 自定义请求头（如 Authorization） */
    private final Map<String, String> headers;
    /** 服务端通过 Mcp-Session-Id 头返回的会话ID */
    private String sessionId;

    public HttpMcpClient(McpClientConfig config) {
        super(config);
        this.serverUrl = config.getUrl();
        this.headers = config.getHeaders();
    }

    @Override
    protected void connect() throws IOException {
        logger.info("HttpMcpClient connecting to: {}", serverUrl);
        // HTTP 模式无需预先建立连接，每次请求独立发起
    }

    @Override
    protected JsonObject sendRequest(String method, JsonObject params) throws IOException {
        JsonObject request = buildJsonRpcRequest(method, params);
        int id = request.get("id").getAsInt();
        String body = gson.toJson(request);
        logger.debug(">>> HTTP [id={}] {}", id, body);

        HttpURLConnection conn = openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");

        // 写入请求体
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int statusCode = conn.getResponseCode();
        // 记录服务端返回的 session id
        String respSessionId = conn.getHeaderField("Mcp-Session-Id");
        if (respSessionId != null && !respSessionId.isEmpty()) {
            this.sessionId = respSessionId;
        }

        if (statusCode < 200 || statusCode >= 300) {
            String errorBody = readStream(conn.getErrorStream());
            throw new IOException("HTTP " + statusCode + " from MCP Server: " + errorBody);
        }

        String contentType = conn.getContentType();
        if (contentType != null && contentType.contains("text/event-stream")) {
            return readSseResponse(conn.getInputStream(), id);
        } else {
            return readJsonResponse(conn.getInputStream(), id);
        }
    }

    @Override
    protected void sendNotification(String method) throws IOException {
        JsonObject notification = buildJsonRpcNotification(method);
        String body = gson.toJson(notification);
        logger.debug(">>> HTTP (notification) {}", body);

        HttpURLConnection conn = openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int statusCode = conn.getResponseCode();
        String respSessionId = conn.getHeaderField("Mcp-Session-Id");
        if (respSessionId != null && !respSessionId.isEmpty()) {
            this.sessionId = respSessionId;
        }

        if (statusCode < 200 || statusCode >= 300) {
            String errorBody = readStream(conn.getErrorStream());
            logger.warn("Notification got HTTP {}: {}", statusCode, errorBody);
        } else {
            // 消费响应体（某些服务端可能返回 204 No Content 或空 body）
            readStream(conn.getInputStream());
        }
    }

    @Override
    public void close() throws IOException {
        logger.info("Closing HttpMcpClient for: {}", serverUrl);
        // HTTP 模式无持久连接需要关闭
    }

    // ==================== 内部方法 ====================

    private HttpURLConnection openConnection() throws IOException {
        URL url = new URL(serverUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);

        // 设置自定义 headers
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }

        // 如果有 session id，带上
        if (sessionId != null) {
            conn.setRequestProperty("Mcp-Session-Id", sessionId);
        }

        return conn;
    }

    /**
     * 读取普通 JSON 响应
     */
    private JsonObject readJsonResponse(InputStream is, int expectedId) throws IOException {
        String responseBody = readStream(is);
        logger.debug("<<< HTTP {}", responseBody);

        JsonObject resp = JsonParser.parseString(responseBody).getAsJsonObject();
        if (resp.has("error")) {
            throw new IOException("JSON-RPC error: " + resp.get("error"));
        }
        return resp;
    }

    /**
     * 读取 SSE (Server-Sent Events) 响应，从事件流中提取匹配 id 的 JSON-RPC 响应
     */
    private JsonObject readSseResponse(InputStream is, int expectedId) throws IOException {
        BufferedReader sseReader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder dataBuffer = new StringBuilder();
        String line;

        while ((line = sseReader.readLine()) != null) {
            if (line.startsWith("data:")) {
                String data = line.substring(5).trim();
                dataBuffer.append(data);
            } else if (line.isEmpty() && dataBuffer.length() > 0) {
                // 空行表示一个 SSE 事件结束
                String eventData = dataBuffer.toString();
                dataBuffer.setLength(0);
                logger.debug("<<< SSE event: {}", eventData);

                try {
                    JsonElement element = JsonParser.parseString(eventData);
                    if (element.isJsonObject()) {
                        JsonObject resp = element.getAsJsonObject();
                        if (resp.has("id") && resp.get("id").getAsInt() == expectedId) {
                            if (resp.has("error")) {
                                throw new IOException("JSON-RPC error: " + resp.get("error"));
                            }
                            return resp;
                        }
                    }
                } catch (JsonSyntaxException e) {
                    logger.warn("Skipping malformed SSE data: {}", eventData);
                }
            }
        }

        throw new IOException("SSE stream ended without receiving response for id=" + expectedId);
    }

    private String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close();
        return sb.toString();
    }
}
