package com.mola.cmd.proxy.app.acp.action;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.mcpauth.McpAuthManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Always-on, loopback-only runtime server for cmd-proxy MCP and MCP authorization. */
public final class CmdProxyControlServer implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(CmdProxyControlServer.class);
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;

    private final int requestedPort;
    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl = "";

    public CmdProxyControlServer() {
        this(0);
    }

    CmdProxyControlServer(int requestedPort) {
        this.requestedPort = requestedPort;
    }

    public synchronized void start() throws IOException {
        if (server != null) return;
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        HttpServer created = HttpServer.create(new InetSocketAddress(loopback, requestedPort), 0);
        ExecutorService createdExecutor = Executors.newCachedThreadPool(new DaemonThreadFactory());
        created.setExecutor(createdExecutor);
        created.createContext(CmdProxyMcpHttpHandler.PATH, new CmdProxyMcpHttpHandler());
        created.createContext("/api/mcp-auth/v1/servers/register",
                exchange -> handleAuth(exchange, true));
        created.createContext("/api/mcp-auth/v1/check",
                exchange -> handleAuth(exchange, false));
        try {
            created.start();
        } catch (RuntimeException e) {
            created.stop(0);
            createdExecutor.shutdownNow();
            throw e;
        }
        server = created;
        executor = createdExecutor;
        baseUrl = "http://127.0.0.1:" + created.getAddress().getPort();
        McpAuthManager.getInstance().setBaseUrl(baseUrl);
        logger.info("cmd-proxy 控制服务已启动: {}", baseUrl);
    }

    public synchronized String getBaseUrl() {
        return baseUrl;
    }

    private void handleAuth(HttpExchange exchange, boolean register) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, error("METHOD_NOT_ALLOWED", "Only POST is supported"));
            return;
        }
        JSONObject result;
        try {
            JSONObject request = JSON.parseObject(new String(readAll(exchange), StandardCharsets.UTF_8));
            result = register ? McpAuthManager.getInstance().register(request)
                    : McpAuthManager.getInstance().check(request);
        } catch (Exception e) {
            result = error("INVALID_REQUEST", e.getMessage());
        }
        int status = "AUTH_SESSION_NOT_FOUND".equals(result.getString("code")) ? 404
                : result.getBooleanValue("success") ? 200 : 400;
        send(exchange, status, result);
    }

    private byte[] readAll(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = exchange.getRequestBody().read(buffer)) >= 0) {
            if (out.size() + read > MAX_REQUEST_BYTES) throw new IOException("Request body too large");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static JSONObject error(String code, String message) {
        JSONObject result = new JSONObject(true);
        result.put("success", false);
        result.put("code", code);
        result.put("message", message == null ? "invalid request" : message);
        return result;
    }

    private static void send(HttpExchange exchange, int status, JSONObject response)
            throws IOException {
        byte[] bytes = JSON.toJSONString(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public synchronized void close() {
        if (server == null) return;
        server.stop(0);
        if (executor != null) executor.shutdownNow();
        McpAuthManager.getInstance().clearBaseUrl(baseUrl);
        server = null;
        executor = null;
        baseUrl = "";
        logger.info("cmd-proxy 控制服务已停止");
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "cmd-proxy-control-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
