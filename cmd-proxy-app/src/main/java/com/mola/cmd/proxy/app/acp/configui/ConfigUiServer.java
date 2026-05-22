package com.mola.cmd.proxy.app.acp.configui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;

/**
 * 内嵌轻量 HTTP 服务，提供 ACP 配置管理页面。
 * 基于 JDK 内置 com.sun.net.httpserver.HttpServer 实现。
 */
public class ConfigUiServer {

    private static final Logger logger = LoggerFactory.getLogger(ConfigUiServer.class);
    private static final String CONFIG_PATH = System.getProperty("user.home") + "/.cmd-proxy/acpConfig.json";

    private final int port;
    private final Runnable refreshCallback;
    private HttpServer server;

    /**
     * @param port            监听端口
     * @param refreshCallback 刷新回调，保存配置后触发 ACP 服务重载
     */
    public ConfigUiServer(int port, Runnable refreshCallback) {
        this.port = port;
        this.refreshCallback = refreshCallback;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));

        // 静态页面
        server.createContext("/", this::handleIndex);
        // REST API
        server.createContext("/api/config", this::handleConfig);
        server.createContext("/api/refresh", this::handleRefresh);
        server.createContext("/api/browse-dir", this::handleBrowseDir);

        server.start();
        logger.info("ConfigUI 已启动: http://localhost:{}", port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            logger.info("ConfigUI 已停止");
        }
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try (InputStream is = getClass().getResourceAsStream("/configui/index.html")) {
            if (is == null) {
                sendResponse(exchange, 404, "text/plain", "Page not found");
                return;
            }
            byte[] bytes = readAllBytes(is);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        }
    }

    private void handleConfig(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        switch (method) {
            case "GET":
                handleGetConfig(exchange);
                break;
            case "POST":
                handlePostConfig(exchange);
                break;
            default:
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
        }
    }

    private void handleGetConfig(HttpExchange exchange) throws IOException {
        Path path = Paths.get(CONFIG_PATH);
        if (!Files.exists(path)) {
            sendResponse(exchange, 200, "application/json", "{}");
            return;
        }
        String raw = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        // 通过 fastjson 解析再序列化，确保输出标准 JSON（消除尾逗号等非标准语法）
        JSONObject json = JSON.parseObject(raw);
        String content = JSON.toJSONString(json, SerializerFeature.PrettyFormat);
        sendResponse(exchange, 200, "application/json", content);
    }

    private void handlePostConfig(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        if (body == null || body.trim().isEmpty()) {
            sendResponse(exchange, 400, "application/json", "{\"error\":\"empty body\"}");
            return;
        }
        // 格式化写入
        JSONObject json = JSON.parseObject(body);
        String formatted = JSON.toJSONString(json, SerializerFeature.PrettyFormat, SerializerFeature.SortField);
        Path path = Paths.get(CONFIG_PATH);
        Files.write(path, formatted.getBytes(StandardCharsets.UTF_8));
        sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
    }

    private void handleRefresh(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            refreshCallback.run();
            sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
        } catch (Exception e) {
            logger.error("刷新 ACP 服务失败", e);
            sendResponse(exchange, 500, "application/json",
                    "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    /**
     * 目录浏览 API：GET /api/browse-dir?path=/some/path
     * 返回指定路径下的子目录列表，用于前端文件夹选择器。
     */
    private void handleBrowseDir(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String query = exchange.getRequestURI().getQuery();
        String dirPath = System.getProperty("user.home");
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && "path".equals(kv[0])) {
                    dirPath = java.net.URLDecoder.decode(kv[1], "UTF-8");
                }
            }
        }

        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            sendResponse(exchange, 200, "application/json",
                    "{\"path\":\"" + dirPath.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"dirs\":[],\"error\":\"not a directory\"}");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"path\":\"").append(dir.getAbsolutePath().replace("\\", "\\\\").replace("\"", "\\\"")).append("\",\"dirs\":[");
        File[] children = dir.listFiles(File::isDirectory);
        if (children != null) {
            java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            boolean first = true;
            for (File child : children) {
                if (child.getName().startsWith(".")) continue;
                if (!first) sb.append(",");
                sb.append("\"").append(child.getName().replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
                first = false;
            }
        }
        sb.append("]}");
        sendResponse(exchange, 200, "application/json", sb.toString());
    }

    private void sendResponse(HttpExchange exchange, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(readAllBytes(is), StandardCharsets.UTF_8);
        }
    }

    private byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = is.read(tmp)) != -1) {
            buffer.write(tmp, 0, n);
        }
        return buffer.toByteArray();
    }
}
