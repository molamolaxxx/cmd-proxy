package com.mola.cmd.proxy.app.acp.configui;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.cert.X509Certificate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内嵌轻量 HTTP 服务，提供 ACP 配置管理页面。
 * 基于 JDK 内置 com.sun.net.httpserver.HttpServer 实现。
 */
public class ConfigUiServer {

    private static final Logger logger = LoggerFactory.getLogger(ConfigUiServer.class);
    private static final String CONFIG_PATH = System.getProperty("user.home") + "/.cmd-proxy/acpConfig.json";

    private static final String UPDATE_JAR_URL = "https://106.54.193.10/download/cmd-proxy.jar";

    private final int port;
    private final Runnable refreshCallback;
    private HttpServer server;
    private ExecutorService executor;

    // 更新状态
    private final AtomicBoolean updating = new AtomicBoolean(false);
    private volatile String updateStatus = "idle"; // idle, downloading, done, error
    private volatile int updateProgress = 0;
    private volatile String updateMessage = "";

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
        executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);

        // 静态页面
        server.createContext("/", this::handleIndex);
        // REST API
        server.createContext("/api/config", this::handleConfig);
        server.createContext("/api/refresh", this::handleRefresh);
        server.createContext("/api/browse-dir", this::handleBrowseDir);
        server.createContext("/api/update-jar", this::handleUpdateJar);
        server.createContext("/api/update-jar/status", this::handleUpdateJarStatus);

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

    // ========== 更新 JAR 相关 ==========

    private void handleUpdateJar(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        if (!updating.compareAndSet(false, true)) {
            sendResponse(exchange, 409, "application/json",
                    "{\"error\":\"更新进行中，请勿重复操作\"}");
            return;
        }
        // 重置状态
        updateStatus = "downloading";
        updateProgress = 0;
        updateMessage = "开始下载...";

        // 异步执行下载替换
        new Thread(this::doUpdateJar, "jar-updater").start();

        sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
    }

    private void handleUpdateJarStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String json = "{\"status\":\"" + updateStatus + "\",\"progress\":" + updateProgress +
                ",\"message\":\"" + updateMessage.replace("\"", "'") + "\"}";
        sendResponse(exchange, 200, "application/json", json);
    }

    private void doUpdateJar() {
        try {
            Path jarPath = getRunningJarPath();
            if (jarPath == null) {
                updateStatus = "error";
                updateMessage = "无法确定当前运行的 JAR 路径";
                updating.set(false);
                return;
            }

            logger.info("开始下载更新: {} -> {}", UPDATE_JAR_URL, jarPath);

            // 创建忽略 SSL 的连接
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);

            URL url = new URL(UPDATE_JAR_URL);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setSSLSocketFactory(sslContext.getSocketFactory());
            conn.setHostnameVerifier((hostname, session) -> true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                updateStatus = "error";
                updateMessage = "下载失败，HTTP " + responseCode;
                updating.set(false);
                return;
            }

            long totalSize = conn.getContentLengthLong();
            Path tmpFile = jarPath.resolveSibling(".cmd-proxy-update.tmp");

            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(tmpFile)) {
                byte[] buf = new byte[8192];
                long downloaded = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    if (totalSize > 0) {
                        updateProgress = (int) (downloaded * 100 / totalSize);
                    }
                    updateMessage = "下载中 " + (downloaded / 1024) + "KB" +
                            (totalSize > 0 ? " / " + (totalSize / 1024) + "KB" : "");
                }
            }

            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            if (isWindows) {
                // Windows下无法原地替换运行中的JAR，先写到.new.jar，再通过脚本异步替换
                Path newJar = jarPath.resolveSibling(".cmd-proxy-update.new.jar");
                Files.move(tmpFile, newJar, StandardCopyOption.REPLACE_EXISTING);

                Path scriptFile = jarPath.resolveSibling(".cmd-proxy-update-replace.bat");
                try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(scriptFile))) {
                    pw.println("@echo off");
                    pw.println("set OLD=" + jarPath.toAbsolutePath());
                    pw.println("set NEW=" + newJar.toAbsolutePath());
                    pw.println("set SELF=" + scriptFile.toAbsolutePath());
                    pw.println(":wait");
                    pw.println("ping 127.0.0.1 -n 2 >nul");
                    pw.println("move /Y \"%NEW%\" \"%OLD%\" >nul 2>&1");
                    pw.println("if errorlevel 1 goto wait");
                    pw.println("del \"%SELF%\"");
                }
                Runtime.getRuntime().exec(
                        new String[]{"cmd.exe", "/c", "start", "/min", "",
                                scriptFile.toAbsolutePath().toString()});

                updateProgress = 100;
                updateStatus = "done";
                updateMessage = "更新已准备完成，关闭程序后自动替换并重启";
                logger.info("JAR 更新脚本已创建: {}", scriptFile);
            } else {
                Files.move(tmpFile, jarPath, StandardCopyOption.REPLACE_EXISTING);

                updateProgress = 100;
                updateStatus = "done";
                updateMessage = "更新完成，重启后生效";
                logger.info("JAR 更新完成: {}", jarPath);
            }

        } catch (Exception e) {
            logger.error("JAR 更新失败", e);
            updateStatus = "error";
            updateMessage = "更新失败: " + e.getMessage();
        } finally {
            updating.set(false);
        }
    }

    private Path getRunningJarPath() {
        try {
            // 通过 CodeSource 获取当前 jar 路径
            URL location = ConfigUiServer.class.getProtectionDomain().getCodeSource().getLocation();
            Path path = Paths.get(location.toURI());
            if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                return path;
            }
        } catch (Exception e) {
            logger.warn("通过 CodeSource 获取 JAR 路径失败", e);
        }
        // 备选：从启动命令解析
        try {
            String cmd = System.getProperty("sun.java.command");
            if (cmd != null) {
                String jarFile = cmd.split("\\s+")[0];
                Path path = Paths.get(jarFile).toAbsolutePath();
                if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                    return path;
                }
            }
        } catch (Exception e) {
            logger.warn("通过 sun.java.command 获取 JAR 路径失败", e);
        }
        return null;
    }
}
