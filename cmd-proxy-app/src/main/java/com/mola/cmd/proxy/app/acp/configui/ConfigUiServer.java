package com.mola.cmd.proxy.app.acp.configui;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.mola.cmd.proxy.app.acp.channel.ChannelConfigFileStore;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.DeepSeekHarnessAcpProvider;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProviderType;
import com.mola.cmd.proxy.app.acp.acpclient.agent.NpmProviderRuntimeManager;
import com.mola.cmd.proxy.app.acp.common.PathResolver;
import com.mola.cmd.proxy.app.acp.starweave.StarweaveSessionApiBridge;
import com.mola.cmd.proxy.app.acp.starweave.StarweaveRequestDeduplicator;
import com.mola.cmd.proxy.app.acp.starweave.StarweaveResourcePayload;
import com.mola.cmd.proxy.app.acp.starweave.StarweaveTeamApiBridge;
import com.mola.cmd.proxy.app.acp.team.TeamSharingStatusRegistry;
import com.mola.cmd.proxy.app.acp.common.InstanceRegistry;
import com.mola.cmd.proxy.app.acp.mcpauth.McpAuthManager;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import com.mola.cmd.proxy.client.conf.CmdProxyConf;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.BiFunction;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.Map;
import java.util.UUID;

/**
 * 内嵌轻量 HTTP 服务，提供 ACP 配置管理页面。
 * 基于 JDK 内置 com.sun.net.httpserver.HttpServer 实现。
 */
public class ConfigUiServer {

    private static final Logger logger = LoggerFactory.getLogger(ConfigUiServer.class);
    private static final String CONFIG_PATH =
            com.mola.cmd.proxy.app.utils.CmdProxyHome.pathOf("acpConfig.json");

    private static final String SECRET_MASK = "********";

    private final int port;
    private final Runnable refreshCallback;
    private final Consumer<String> refreshRobotCallback;
    private final Supplier<java.util.Map<String, String>> channelStatusSupplier;
    private final Supplier<java.util.Map<String, String>> channelErrorSupplier;
    private final BiFunction<String, Boolean, Boolean> channelInboundUpdater;
    private final BiFunction<String, Boolean, Boolean> channelPrivateChatUpdater;
    private final Supplier<List<Map<String, Object>>> channelBindingTargetSupplier;
    private final BiConsumer<String, String> refreshChannelCallback;
    private final AgentResourceBrowser agentResourceBrowser = new AgentResourceBrowser();
    private final StarweaveRequestDeduplicator starweaveRequests =
            new StarweaveRequestDeduplicator();
    private HttpServer server;
    private ExecutorService executor;
    private ExecutorService starweaveStreamExecutor;
    private final Semaphore starweaveStreamSlots = new Semaphore(16);

    // 更新状态
    private final AtomicBoolean updating = new AtomicBoolean(false);
    private volatile String updateStatus = "idle"; // idle, downloading, done, error
    private volatile int updateProgress = 0;
    private volatile String updateMessage = "";

    /**
     * @param port            监听端口
     * @param refreshCallback 用户点击“刷新服务”后触发 ACP 服务重载
     * @param refreshRobotCallback 按 robot 维度刷新的回调
     */
    public ConfigUiServer(int port, Runnable refreshCallback, Consumer<String> refreshRobotCallback) {
        this(port, refreshCallback, refreshRobotCallback,
                java.util.Collections::emptyMap, java.util.Collections::emptyMap,
                (channelId, enabled) -> {
                    throw new IllegalStateException("channel service is not running");
                }, (channelId, enabled) -> {
                    throw new IllegalStateException("channel service is not running");
                }, java.util.Collections::emptyList,
                (previousId, channelId) -> {
                    throw new IllegalStateException("channel service is not running");
                });
    }

    public ConfigUiServer(int port, Runnable refreshCallback,
                          Consumer<String> refreshRobotCallback,
                          Supplier<java.util.Map<String, String>> channelStatusSupplier,
                          Supplier<java.util.Map<String, String>> channelErrorSupplier) {
        this(port, refreshCallback, refreshRobotCallback, channelStatusSupplier,
                channelErrorSupplier, (channelId, enabled) -> {
                    throw new IllegalStateException("channel service is not running");
                }, (channelId, enabled) -> {
                    throw new IllegalStateException("channel service is not running");
                }, java.util.Collections::emptyList,
                (previousId, channelId) -> {
                    throw new IllegalStateException("channel service is not running");
                });
    }

    public ConfigUiServer(int port, Runnable refreshCallback,
                          Consumer<String> refreshRobotCallback,
                          Supplier<java.util.Map<String, String>> channelStatusSupplier,
                          Supplier<java.util.Map<String, String>> channelErrorSupplier,
                          BiFunction<String, Boolean, Boolean> channelInboundUpdater) {
        this(port, refreshCallback, refreshRobotCallback, channelStatusSupplier,
                channelErrorSupplier, channelInboundUpdater,
                (channelId, enabled) -> {
                    throw new IllegalStateException("channel service is not running");
                },
                java.util.Collections::emptyList,
                (previousId, channelId) -> {
                    throw new IllegalStateException("channel service is not running");
                });
    }

    public ConfigUiServer(int port, Runnable refreshCallback,
                          Consumer<String> refreshRobotCallback,
                          Supplier<java.util.Map<String, String>> channelStatusSupplier,
                          Supplier<java.util.Map<String, String>> channelErrorSupplier,
                          BiFunction<String, Boolean, Boolean> channelInboundUpdater,
                          Supplier<List<Map<String, Object>>> channelBindingTargetSupplier) {
        this(port, refreshCallback, refreshRobotCallback, channelStatusSupplier,
                channelErrorSupplier, channelInboundUpdater,
                (channelId, enabled) -> {
                    throw new IllegalStateException("channel service is not running");
                },
                channelBindingTargetSupplier,
                (previousId, channelId) -> {
                    throw new IllegalStateException("channel service is not running");
                });
    }

    public ConfigUiServer(int port, Runnable refreshCallback,
                          Consumer<String> refreshRobotCallback,
                          Supplier<java.util.Map<String, String>> channelStatusSupplier,
                          Supplier<java.util.Map<String, String>> channelErrorSupplier,
                          BiFunction<String, Boolean, Boolean> channelInboundUpdater,
                          BiFunction<String, Boolean, Boolean> channelPrivateChatUpdater,
                          Supplier<List<Map<String, Object>>> channelBindingTargetSupplier,
                          BiConsumer<String, String> refreshChannelCallback) {
        this.port = port;
        this.refreshCallback = refreshCallback;
        this.refreshRobotCallback = refreshRobotCallback;
        this.channelStatusSupplier = channelStatusSupplier;
        this.channelErrorSupplier = channelErrorSupplier;
        this.channelInboundUpdater = channelInboundUpdater;
        this.channelPrivateChatUpdater = channelPrivateChatUpdater;
        this.channelBindingTargetSupplier = channelBindingTargetSupplier;
        this.refreshChannelCallback = refreshChannelCallback;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        executor = Executors.newFixedThreadPool(4);
        starweaveStreamExecutor = Executors.newFixedThreadPool(16, runnable -> {
            Thread thread = new Thread(runnable, "starweave-session-stream");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);

        // ConfigUI 自带静态资源。仅暴露明确登记的资源，避免把 classpath 变成文件服务。
        server.createContext("/assets/", this::handleStaticAsset);
        // 静态页面
        server.createContext("/", this::handleIndex);
        // 环境列表（不代理，始终由本进程扫描主机级注册表）
        server.createContext("/api/instances", this::handleInstances);
        // REST API：带 instance 参数且非本环境时，转发到目标环境的 ConfigUI
        server.createContext("/api/config", proxied(this::handleConfig));
        server.createContext("/api/channels/status", proxied(this::handleChannelStatus));
        server.createContext("/api/channels/inbound", proxied(this::handleChannelInbound));
        server.createContext("/api/channels/private-chat",
                proxied(this::handleChannelPrivateChat));
        server.createContext("/api/channels/binding-targets",
                proxied(this::handleChannelBindingTargets));
        server.createContext("/api/team/sharing-status",
                proxied(this::handleTeamSharingStatus));
        server.createContext("/api/mcp-auth/v1/servers", proxied(this::handleMcpServers));
        server.createContext("/api/mcp-auth/v1/principals", proxied(this::handleMcpPrincipals));
        server.createContext("/api/mcp-auth/v1/policies", proxied(this::handleMcpPolicies));
        server.createContext("/api/refresh", proxied(this::handleRefresh));
        server.createContext("/api/refresh-robot", proxied(this::handleRefreshRobot));
        server.createContext("/api/refresh-channel", proxied(this::handleRefreshChannel));
        server.createContext("/api/browse-dir", proxied(this::handleBrowseDir));
        server.createContext("/api/agent-resources/tree",
                proxied(this::handleAgentResourceTree));
        server.createContext("/api/agent-resources/content",
                proxied(this::handleAgentResourceContent));
        server.createContext("/api/starweave/v1/sessions",
                proxied(this::handleStarweaveSessions));
        server.createContext("/api/starweave/v1/sessions/open",
                proxied(exchange -> handleStarweaveCommand(exchange, "open")));
        server.createContext("/api/starweave/v1/sessions/messages",
                proxied(exchange -> handleStarweaveCommand(exchange, "messages")));
        server.createContext("/api/starweave/v1/sessions/uploads",
                proxied(this::handleStarweaveUpload));
        server.createContext("/api/starweave/v1/sessions/resources",
                proxied(exchange -> handleStarweaveResources(exchange, "list")));
        server.createContext("/api/starweave/v1/sessions/resources/preview",
                proxied(exchange -> handleStarweaveResources(exchange, "preview")));
        server.createContext("/api/starweave/v1/sessions/resources/download",
                proxied(exchange -> handleStarweaveResources(exchange, "download")));
        server.createContext("/api/starweave/v1/sessions/cancel",
                proxied(exchange -> handleStarweaveCommand(exchange, "cancel")));
        server.createContext("/api/starweave/v1/sessions/new",
                proxied(exchange -> handleStarweaveCommand(exchange, "new")));
        server.createContext("/api/starweave/v1/sessions/restore",
                proxied(exchange -> handleStarweaveCommand(exchange, "restore")));
        server.createContext("/api/starweave/v1/sessions/delete",
                proxied(exchange -> handleStarweaveCommand(exchange, "delete")));
        server.createContext("/api/starweave/v1/sessions/events",
                proxied(this::handleStarweaveEvents));
        server.createContext("/api/starweave/v1/sessions/stream",
                this::handleStarweaveStream);
        server.createContext("/api/starweave/v1/teams",
                proxied(exchange -> handleStarweaveTeams(exchange, "list")));
        server.createContext("/api/starweave/v1/teams/sources",
                proxied(exchange -> handleStarweaveTeams(exchange, "sources")));
        server.createContext("/api/starweave/v1/teams/create",
                proxied(exchange -> handleStarweaveTeams(exchange, "create")));
        server.createContext("/api/starweave/v1/teams/delete",
                proxied(exchange -> handleStarweaveTeams(exchange, "delete")));
        server.createContext("/api/starweave/v1/teams/member",
                proxied(exchange -> handleStarweaveTeams(exchange, "member")));
        server.createContext("/api/starweave/v1/teams/uploads",
                proxied(exchange -> handleStarweaveTeams(exchange, "upload")));
        server.createContext("/api/starweave/v1/teams/events",
                proxied(exchange -> handleStarweaveTeams(exchange, "events")));
        server.createContext("/api/starweave/v1/teams/stream",
                this::handleStarweaveTeamStream);
        server.createContext("/api/starweave/v1/teams/resources",
                proxied(exchange -> handleStarweaveTeamResources(exchange, "list")));
        server.createContext("/api/starweave/v1/teams/resources/preview",
                proxied(exchange -> handleStarweaveTeamResources(exchange, "preview")));
        server.createContext("/api/starweave/v1/teams/resources/download",
                proxied(exchange -> handleStarweaveTeamResources(exchange, "download")));
        server.createContext("/api/update-jar", proxied(this::handleUpdateJar));
        server.createContext("/api/update-jar/status", proxied(this::handleUpdateJarStatus));
        server.createContext("/api/provider-runtime/releases",
                proxied(this::handleProviderRuntimeReleases));
        server.createContext("/api/provider-runtime/status",
                proxied(this::handleProviderRuntimeStatus));
        server.createContext("/api/provider-runtime/install",
                proxied(this::handleProviderRuntimeInstall));
        server.createContext("/api/provider-runtime/job",
                proxied(this::handleProviderRuntimeJob));

        server.start();
        logger.info("ConfigUI 已启动: http://localhost:{}", port);
    }

    private void handleMcpRegister(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "application/json", "{\"success\":false,\"code\":\"METHOD_NOT_ALLOWED\"}");
            return;
        }
        JSONObject result;
        try { result = McpAuthManager.getInstance().register(JSON.parseObject(readBody(exchange))); }
        catch (Exception e) { result = apiError("INVALID_REQUEST", e.getMessage()); }
        int status = "AUTH_SESSION_NOT_FOUND".equals(result.getString("code")) ? 404
                : result.getBooleanValue("success") ? 200 : 400;
        sendResponse(exchange, status, "application/json", JSON.toJSONString(result));
    }

    private void handleMcpCheck(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "application/json", "{\"success\":false,\"code\":\"METHOD_NOT_ALLOWED\"}");
            return;
        }
        JSONObject result;
        try { result = McpAuthManager.getInstance().check(JSON.parseObject(readBody(exchange))); }
        catch (Exception e) { result = apiError("INVALID_REQUEST", e.getMessage()); }
        sendResponse(exchange, result.getBooleanValue("success") ? 200 : 400,
                "application/json", JSON.toJSONString(result));
    }

    private void handleMcpServers(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed"); return;
        }
        JSONObject result = new JSONObject(true);
        result.put("servers", McpAuthManager.getInstance().serverSnapshot());
        sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
    }

    private void handleMcpPrincipals(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed"); return;
        }
        JSONObject result = new JSONObject(true);
        result.put("principals", McpAuthManager.getInstance().principalSnapshot());
        sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
    }

    private void handleMcpPolicies(HttpExchange exchange) throws IOException {
        if (!"PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed"); return;
        }
        JSONObject result;
        try { result = McpAuthManager.getInstance().updatePolicy(JSON.parseObject(readBody(exchange))); }
        catch (Exception e) { result = apiError("INVALID_REQUEST", e.getMessage()); }
        sendResponse(exchange, result.getBooleanValue("success") ? 200 : 400,
                "application/json", JSON.toJSONString(result));
    }

    private JSONObject apiError(String code, String message) {
        JSONObject result = new JSONObject(true); result.put("success", false);
        result.put("code", code); result.put("message", message == null ? "invalid request" : message);
        return result;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            logger.info("ConfigUI 已停止");
        }
        if (starweaveStreamExecutor != null) {
            starweaveStreamExecutor.shutdownNow();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    int getBoundPort() {
        HttpServer current = server;
        if (current == null) throw new IllegalStateException("ConfigUI is not running");
        return current.getAddress().getPort();
    }

    // ==================== 多环境：环境列表与跨环境代理 ====================

    /** 代理请求标记，避免转发成环 */
    private static final String PROXY_HEADER = "X-Cmd-Proxy-Proxied";

    /**
     * 包装 handler：请求带 {@code instance} 参数且指向其它环境时，
     * 转发到该环境 ConfigUI 的同名接口，否则按本地逻辑处理。
     * 这样前端始终同源，无需 CORS，且 refresh/update-jar 这类必须在目标进程内执行的操作也能正确落地。
     */
    private com.sun.net.httpserver.HttpHandler proxied(com.sun.net.httpserver.HttpHandler local) {
        return exchange -> {
            String target = param(exchange, "instance");
            boolean alreadyProxied = exchange.getRequestHeaders().getFirst(PROXY_HEADER) != null;
            if (target == null || target.isEmpty() || alreadyProxied
                    || target.equals(CmdProxyHome.instanceId())) {
                local.handle(exchange);
                return;
            }
            forward(exchange, target);
        };
    }

    /** 环境列表：主机上所有存活环境，供前端渲染环境页签 */
    private void handleInstances(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        List<InstanceRegistry.InstanceInfo> instances = InstanceRegistry.listAll();
        sendResponse(exchange, 200, "application/json",
                JSON.toJSONString(instances, SerializerFeature.DisableCircularReferenceDetect));
    }

    /** 把当前请求原样转发到目标环境的 ConfigUI */
    private void forward(HttpExchange exchange, String instanceId) throws IOException {
        InstanceRegistry.InstanceInfo target = null;
        for (InstanceRegistry.InstanceInfo info : InstanceRegistry.listAll()) {
            if (instanceId.equals(info.instanceId)) {
                target = info;
                break;
            }
        }
        if (target == null) {
            sendResponse(exchange, 404, "application/json",
                    "{\"ok\":false,\"error\":\"环境不存在或已退出: " + jsonEscape(instanceId) + "\"}");
            return;
        }
        if (target.configUiPort <= 0) {
            sendResponse(exchange, 409, "application/json",
                    "{\"ok\":false,\"error\":\"环境 " + jsonEscape(target.home)
                            + " 未开启配置页，无法远程编辑\"}");
            return;
        }

        byte[] body = readAllBytes(exchange.getRequestBody());
        String query = stripInstanceParam(exchange.getRequestURI().getRawQuery());
        String url = "http://127.0.0.1:" + target.configUiPort + exchange.getRequestURI().getPath()
                + (query.isEmpty() ? "" : "?" + query);

        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(exchange.getRequestMethod());
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty(PROXY_HEADER, "1");
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType != null) {
                conn.setRequestProperty("Content-Type", contentType);
            }
            if (body.length > 0) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] respBytes = is == null ? new byte[0] : readAllBytes(is);
            String respType = conn.getContentType();
            sendResponse(exchange, code,
                    respType == null ? "application/json" : respType,
                    new String(respBytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warn("跨环境转发失败: url={}", url, e);
            sendResponse(exchange, 502, "application/json",
                    "{\"ok\":false,\"error\":\"目标环境无响应: " + jsonEscape(e.getMessage()) + "\"}");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 转发时去掉 instance 参数，使目标环境按本地逻辑处理 */
    private String stripInstanceParam(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty() || pair.equals("instance") || pair.startsWith("instance=")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(pair);
        }
        return sb.toString();
    }

    /** 读取 query 参数（已 URL 解码） */
    private String param(HttpExchange exchange, String key) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && key.equals(kv[0])) {
                return java.net.URLDecoder.decode(kv[1], "UTF-8");
            }
        }
        return null;
    }

    private String jsonEscape(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
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

    private void handleStaticAsset(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String requestPath = exchange.getRequestURI().getPath();
        if (!"/assets/MaterialIcons-Regular.woff2".equals(requestPath)) {
            sendResponse(exchange, 404, "text/plain", "Asset not found");
            return;
        }
        try (InputStream input = getClass().getResourceAsStream(
                "/configui/assets/MaterialIcons-Regular.woff2")) {
            if (input == null) {
                sendResponse(exchange, 404, "text/plain", "Asset not found");
                return;
            }
            byte[] bytes = readAllBytes(input);
            exchange.getResponseHeaders().set("Content-Type", "font/woff2");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if ("HEAD".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
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
        appendDshRuntimeProjection(json);
        appendProviderRuntimeProjection(json);
        maskChannelSecrets(json);
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
        if (json.getJSONArray("robots") != null) {
            for (int i = 0; i < json.getJSONArray("robots").size(); i++) {
                JSONObject robot = json.getJSONArray("robots").getJSONObject(i);
                if (robot != null) {
                    robot.remove("_dshProfileBundles");
                    robot.remove("_dshProfileError");
                    robot.remove("_providerRuntime");
                }
                if (robot != null && robot.getBooleanValue("onlySubAgent")
                        && robot.getBooleanValue("onlyTeamMember")) {
                    sendResponse(exchange, 400, "application/json",
                            "{\"error\":\"onlySubAgent and onlyTeamMember cannot both be true\"}");
                    return;
                }
            }
        }
        // 全局代理已改为批量操作；清理旧配置字段，避免继续维护独立状态。
        json.remove("globalProxyEnabled");
        ChannelConfigFileStore.saveUiConfig(json, SECRET_MASK);
        sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
    }

    /** Adds read-only DSH profile state to GET /api/config; POST strips it again. */
    private void appendDshRuntimeProjection(JSONObject json) {
        com.alibaba.fastjson.JSONArray robots = json.getJSONArray("robots");
        if (robots == null) return;
        DeepSeekHarnessAcpProvider provider = new DeepSeekHarnessAcpProvider();
        for (int i = 0; i < robots.size(); i++) {
            JSONObject robot = robots.getJSONObject(i);
            if (robot == null || !"DEEPSEEK_HARNESS_ACP".equalsIgnoreCase(
                    robot.getString("agentProvider"))) {
                continue;
            }
            try {
                AcpRobotParam param = robot.toJavaObject(AcpRobotParam.class);
                robot.put("_dshProfileBundles", provider.getInstalledProfileBundles(param));
            } catch (Exception e) {
                logger.warn("读取 DSH profile bundles 失败, robot={}", robot.getString("name"), e);
                robot.put("_dshProfileBundles", java.util.Collections.emptyList());
                robot.put("_dshProfileError", e.getMessage());
            }
        }
    }

    /** Adds installed npm runtime versions without persisting projection fields. */
    private void appendProviderRuntimeProjection(JSONObject json) {
        com.alibaba.fastjson.JSONArray robots = json.getJSONArray("robots");
        if (robots == null) return;
        NpmProviderRuntimeManager manager = NpmProviderRuntimeManager.getInstance();
        for (int i = 0; i < robots.size(); i++) {
            JSONObject robot = robots.getJSONObject(i);
            if (robot == null) continue;
            AgentProviderType type = AgentProviderType.fromString(robot.getString("agentProvider"));
            if (!manager.supports(type)) continue;
            NpmProviderRuntimeManager.RuntimeStatus status = manager.status(type);
            JSONObject runtime = new JSONObject(true);
            runtime.put("provider", type.name());
            runtime.put("packageName", status.getPackageName());
            runtime.put("selectedVersion", robot.getString("providerVersion"));
            runtime.put("installedVersions", status.getInstalledVersions());
            runtime.put("defaultVersion", status.getDefaultVersion());
            robot.put("_providerRuntime", runtime);
        }
    }

    private void handleProviderRuntimeReleases(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            AgentProviderType type = requiredNpmProvider(exchange);
            NpmProviderRuntimeManager.ReleaseCatalog catalog =
                    NpmProviderRuntimeManager.getInstance().fetchReleases(type);
            JSONObject result = new JSONObject(true);
            result.put("provider", catalog.getProvider().name());
            result.put("packageName", catalog.getPackageName());
            result.put("distTags", catalog.getDistTags());
            result.put("versions", catalog.getVersions());
            result.put("installedVersions", catalog.getInstalledVersions());
            sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_PROVIDER", e.getMessage())));
        } catch (Exception e) {
            logger.warn("读取 Provider 发行版本失败", e);
            sendResponse(exchange, 502, "application/json",
                    JSON.toJSONString(apiError("REGISTRY_UNAVAILABLE", e.getMessage())));
        }
    }

    private void handleProviderRuntimeStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            AgentProviderType type = requiredNpmProvider(exchange);
            NpmProviderRuntimeManager.RuntimeStatus status =
                    NpmProviderRuntimeManager.getInstance().status(type);
            JSONObject result = new JSONObject(true);
            result.put("provider", status.getProvider().name());
            result.put("packageName", status.getPackageName());
            result.put("installedVersions", status.getInstalledVersions());
            result.put("defaultVersion", status.getDefaultVersion());
            sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_PROVIDER", e.getMessage())));
        }
    }

    private void handleProviderRuntimeInstall(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            JSONObject request = JSON.parseObject(readBody(exchange));
            AgentProviderType type = AgentProviderType.fromString(
                    request == null ? null : request.getString("provider"));
            NpmProviderRuntimeManager manager = NpmProviderRuntimeManager.getInstance();
            if (!manager.supportsManagedInstall(type)) {
                throw new IllegalArgumentException("provider does not use managed installation: "
                        + type);
            }
            String version = request == null ? null : request.getString("version");
            Map<String, String> environment = new java.util.LinkedHashMap<>(System.getenv());
            String home = System.getProperty("user.home");
            environment.put("PATH", PathResolver.enrichPath(home,
                    environment.get("PATH") == null ? "" : environment.get("PATH")));
            NpmProviderRuntimeManager.InstallJob job =
                    manager.startInstall(type, version, environment);
            sendResponse(exchange, 202, "application/json",
                    JSON.toJSONString(providerJobJson(job)));
        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_REQUEST", e.getMessage())));
        }
    }

    private void handleProviderRuntimeJob(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String jobId = param(exchange, "jobId");
        NpmProviderRuntimeManager.InstallJob job =
                NpmProviderRuntimeManager.getInstance().job(jobId);
        if (job == null) {
            sendResponse(exchange, 404, "application/json",
                    JSON.toJSONString(apiError("JOB_NOT_FOUND", "安装任务不存在")));
            return;
        }
        sendResponse(exchange, 200, "application/json",
                JSON.toJSONString(providerJobJson(job)));
    }

    private AgentProviderType requiredNpmProvider(HttpExchange exchange) throws IOException {
        AgentProviderType type = AgentProviderType.fromString(param(exchange, "provider"));
        if (!NpmProviderRuntimeManager.getInstance().supports(type)) {
            throw new IllegalArgumentException("provider is not npm-managed: " + type);
        }
        return type;
    }

    private JSONObject providerJobJson(NpmProviderRuntimeManager.InstallJob job) {
        JSONObject result = new JSONObject(true);
        result.put("jobId", job.getJobId());
        result.put("provider", job.getProvider().name());
        result.put("version", job.getVersion());
        result.put("status", job.getStatus());
        result.put("progress", job.getProgress());
        result.put("message", job.getMessage());
        return result;
    }

    private void handleChannelStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        JSONObject result = new JSONObject();
        result.put("instanceId", CmdProxyHome.instanceId());
        result.put("statuses", channelStatusSupplier.get());
        result.put("errors", channelErrorSupplier.get());
        sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
    }

    private void handleStarweaveSessions(HttpExchange exchange) throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            JSONObject data = new JSONObject(true);
            data.put("instanceId", CmdProxyHome.instanceId());
            data.put("sessions", StarweaveSessionApiBridge.list());
            sendResponse(exchange, 200, "application/json",
                    JSON.toJSONString(starweaveEnvelope(null, true, "OK", "", data)));
        } catch (Exception e) {
            sendStarweaveError(exchange, null, e);
        }
    }

    private void handleStarweaveEvents(HttpExchange exchange) throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            String groupId = param(exchange, "groupId");
            String sessionId = param(exchange, "sessionId");
            long afterSeq = parseLong(param(exchange, "after"), 0L);
            JSONObject data = StarweaveSessionApiBridge.eventBatch(
                    groupId, sessionId, afterSeq, null);
            sendResponse(exchange, 200, "application/json",
                    JSON.toJSONString(starweaveEnvelope(null, true, "OK", "", data)));
        } catch (Exception e) {
            sendStarweaveError(exchange, null, e);
        }
    }

    /**
     * Bounded SSE entry point. The HttpServer worker only validates and hands the
     * exchange to the dedicated stream pool, so ordinary ConfigUI requests cannot
     * be starved by long-lived browsers.
     */
    private void handleStarweaveStream(HttpExchange exchange) throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        if (!starweaveStreamSlots.tryAcquire()) {
            sendResponse(exchange, 429, "application/json",
                    JSON.toJSONString(apiError("STREAM_LIMIT", "会话流连接数已达上限")));
            return;
        }
        try {
            starweaveStreamExecutor.execute(() -> {
                try {
                    String target = uncheckedParam(exchange, "instance");
                    if (target != null && !target.isEmpty()
                            && !target.equals(CmdProxyHome.instanceId())) {
                        forwardStarweaveStream(exchange, target);
                    } else {
                        serveStarweaveStream(exchange);
                    }
                } catch (Exception e) {
                    logger.debug("Starweave 会话流已结束: {}", e.getMessage());
                    try { exchange.close(); } catch (Exception ignored) { }
                } finally {
                    starweaveStreamSlots.release();
                }
            });
        } catch (RejectedExecutionException stopped) {
            starweaveStreamSlots.release();
            sendResponse(exchange, 503, "application/json",
                    JSON.toJSONString(apiError("STREAM_STOPPED", "会话流服务正在停止")));
        }
    }

    private void serveStarweaveStream(HttpExchange exchange) throws IOException {
        String groupId = param(exchange, "groupId");
        String sessionId = param(exchange, "sessionId");
        String generationText = param(exchange, "generation");
        if (groupId == null || groupId.trim().isEmpty()
                || sessionId == null || sessionId.trim().isEmpty()
                || generationText == null || generationText.trim().isEmpty()) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_REQUEST",
                            "groupId、sessionId、generation 均不能为空")));
            return;
        }
        long generation = parseLong(generationText, -1L);
        if (generation < 1L) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_GENERATION", "generation 必须为正整数")));
            return;
        }
        long after = parseLong(param(exchange, "after"), 0L);
        String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (lastEventId != null && !lastEventId.trim().isEmpty()) {
            after = Math.max(after, parseLong(lastEventId, after));
        }

        // Validate ownership/current index before committing streaming headers.
        StarweaveSessionApiBridge.eventBatch(groupId, sessionId, after, generation);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(25);
        try (OutputStream output = exchange.getResponseBody()) {
            writeSseComment(output, "starweave stream ready");
            while (System.currentTimeMillis() < deadline
                    && !Thread.currentThread().isInterrupted()) {
                JSONObject batch = StarweaveSessionApiBridge.awaitEventBatch(
                        groupId, sessionId, after, generation, 10_000L);
                if (batch.getBooleanValue("resyncRequired")) {
                    JSONObject payload = new JSONObject(true);
                    payload.put("firstAvailableSeq", batch.getLongValue("firstAvailableSeq"));
                    payload.put("latestSeq", batch.getLongValue("latestSeq"));
                    writeSse(output, null, "RESYNC_REQUIRED", payload.toJSONString());
                    break;
                }
                com.alibaba.fastjson.JSONArray values = batch.getJSONArray("events");
                if (values == null || values.isEmpty()) {
                    writeSseComment(output, "heartbeat");
                    continue;
                }
                for (int i = 0; i < values.size(); i++) {
                    JSONObject event = values.getJSONObject(i);
                    after = Math.max(after, event.getLongValue("eventSeq"));
                    writeSse(output, Long.toString(event.getLongValue("eventSeq")),
                            "starweave", event.toJSONString());
                }
            }
        }
    }

    private void forwardStarweaveStream(HttpExchange exchange, String instanceId)
            throws IOException {
        InstanceRegistry.InstanceInfo target = null;
        for (InstanceRegistry.InstanceInfo info : InstanceRegistry.listAll()) {
            if (instanceId.equals(info.instanceId)) {
                target = info;
                break;
            }
        }
        if (target == null || target.configUiPort <= 0) {
            sendResponse(exchange, 404, "application/json",
                    JSON.toJSONString(apiError("INSTANCE_NOT_FOUND", "目标环境不存在或未开启配置页")));
            return;
        }
        String query = stripInstanceParam(exchange.getRequestURI().getRawQuery());
        URL url = new URL("http://127.0.0.1:" + target.configUiPort
                + exchange.getRequestURI().getPath() + (query.isEmpty() ? "" : "?" + query));
        java.net.HttpURLConnection connection =
                (java.net.HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(35_000);
            connection.setRequestProperty(PROXY_HEADER, "1");
            String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
            if (lastEventId != null) connection.setRequestProperty("Last-Event-ID", lastEventId);
            int status = connection.getResponseCode();
            String contentType = connection.getContentType();
            exchange.getResponseHeaders().set("Content-Type", contentType == null
                    ? "text/event-stream; charset=utf-8" : contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
            exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
            exchange.sendResponseHeaders(status, 0);
            InputStream input = status >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) return;
            try (InputStream source = input; OutputStream output = exchange.getResponseBody()) {
                byte[] buffer = new byte[4096];
                int length;
                while ((length = source.read(buffer)) >= 0) {
                    output.write(buffer, 0, length);
                    output.flush();
                }
            }
        } finally {
            connection.disconnect();
            exchange.close();
        }
    }

    private static void writeSse(OutputStream output, String id, String event,
                                 String data) throws IOException {
        StringBuilder frame = new StringBuilder();
        if (id != null) frame.append("id: ").append(id).append('\n');
        if (event != null) frame.append("event: ").append(event).append('\n');
        for (String line : data.split("\\r?\\n", -1)) {
            frame.append("data: ").append(line).append('\n');
        }
        frame.append('\n');
        output.write(frame.toString().getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void writeSseComment(OutputStream output, String comment)
            throws IOException {
        output.write((": " + comment + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private String uncheckedParam(HttpExchange exchange, String key) {
        try {
            return param(exchange, key);
        } catch (IOException impossible) {
            return null;
        }
    }

    private void handleStarweaveCommand(HttpExchange exchange, String action)
            throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        JSONObject request = null;
        String requestId = null;
        try {
            request = JSON.parseObject(readBody(exchange, 2L * 1024L * 1024L));
            if (request == null) request = new JSONObject(true);
            requestId = request.getString("requestId");
            final JSONObject commandRequest = request;
            JSONObject data = starweaveRequests.execute(requestId,
                    action + ":" + request.toJSONString(),
                    () -> executeStarweaveCommand(action, commandRequest));
            sendResponse(exchange, 200, "application/json",
                    JSON.toJSONString(starweaveCommandEnvelope(requestId, data)));
        } catch (Exception e) {
            sendStarweaveError(exchange, requestId, e);
        }
    }

    private void handleStarweaveUpload(HttpExchange exchange) throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String requestId = null;
        try {
            JSONObject request = JSON.parseObject(readBody(exchange,
                    28L * 1024L * 1024L));
            if (request == null) throw new IllegalArgumentException("request body is required");
            requestId = request.getString("requestId");
            JSONObject data = StarweaveSessionApiBridge.upload(
                    request.getString("groupId"), request.getString("sessionId"),
                    request.getLongValue("generation"), request.getString("fileName"),
                    request.getString("contentBase64"));
            sendResponse(exchange, 200, "application/json",
                    JSON.toJSONString(starweaveEnvelope(
                            requestId, true, "OK", "", data)));
        } catch (Exception e) {
            sendStarweaveError(exchange, requestId, e);
        }
    }

    private void handleStarweaveResources(HttpExchange exchange, String action)
            throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            String groupId = param(exchange, "groupId");
            String sessionId = param(exchange, "sessionId");
            long generation = parseLong(param(exchange, "generation"), -1L);
            String resourceId = param(exchange, "resourceId");
            if ("download".equals(action)) {
                StarweaveResourcePayload resource = StarweaveSessionApiBridge.downloadResource(
                        groupId, sessionId, generation, resourceId);
                exchange.getResponseHeaders().set("Content-Type", resource.getContentType());
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                String encoded = java.net.URLEncoder.encode(
                        resource.getFileName(), "UTF-8").replace("+", "%20");
                exchange.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename*=UTF-8''" + encoded);
                byte[] bytes = resource.getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
                return;
            }
            JSONObject data = new JSONObject(true);
            if ("preview".equals(action)) {
                data.put("resource", StarweaveSessionApiBridge.previewResource(
                        groupId, sessionId, generation, resourceId));
            } else {
                data.put("resources", StarweaveSessionApiBridge.resources(
                        groupId, sessionId, generation));
            }
            sendResponse(exchange, 200, "application/json",
                    JSON.toJSONString(starweaveEnvelope(null, true, "OK", "", data)));
        } catch (Exception e) {
            sendStarweaveError(exchange, null, e);
        }
    }

    private void handleStarweaveTeams(HttpExchange exchange, String action)
            throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        boolean mutation = "create".equals(action) || "delete".equals(action)
                || "member".equals(action) || "upload".equals(action);
        String expectedMethod = mutation ? "POST" : "GET";
        if (!expectedMethod.equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            JSONObject data;
            if ("events".equals(action)) {
                data = new JSONObject(true);
                data.put("events", StarweaveTeamApiBridge.events(
                        parseLong(param(exchange, "after"), 0L),
                        param(exchange, "teamId"), param(exchange, "teamMemberId")));
            } else if ("list".equals(action)) {
                data = StarweaveTeamApiBridge.list();
            } else if ("sources".equals(action)) {
                data = StarweaveTeamApiBridge.sources();
            } else {
                JSONObject request = JSON.parseObject(readBody(exchange,
                        "upload".equals(action) ? 28L * 1024L * 1024L : 256L * 1024L));
                data = "create".equals(action)
                        ? StarweaveTeamApiBridge.create(request)
                        : "upload".equals(action)
                        ? StarweaveTeamApiBridge.upload(request)
                        : "member".equals(action)
                        ? StarweaveTeamApiBridge.member(request)
                        : StarweaveTeamApiBridge.delete(request);
            }
            boolean accepted = !data.containsKey("accepted")
                    || data.getBooleanValue("accepted");
            sendResponse(exchange, accepted ? 200 : 422, "application/json",
                    JSON.toJSONString(starweaveEnvelope(null, accepted,
                            data.getString("code"), data.getString("message"), data)));
        } catch (Exception e) {
            sendStarweaveError(exchange, null, e);
        }
    }

    /** Team sessions use the same immediate, bounded SSE delivery as normal sessions. */
    private void handleStarweaveTeamStream(HttpExchange exchange) throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        if (!starweaveStreamSlots.tryAcquire()) {
            sendResponse(exchange, 429, "application/json",
                    JSON.toJSONString(apiError("STREAM_LIMIT", "会话流连接数已达上限")));
            return;
        }
        try {
            starweaveStreamExecutor.execute(() -> {
                try {
                    String target = uncheckedParam(exchange, "instance");
                    if (target != null && !target.isEmpty()
                            && !target.equals(CmdProxyHome.instanceId())) {
                        forwardStarweaveStream(exchange, target);
                    } else {
                        serveStarweaveTeamStream(exchange);
                    }
                } catch (Exception e) {
                    logger.debug("Starweave 团队会话流已结束: {}", e.getMessage());
                    try { exchange.close(); } catch (Exception ignored) { }
                } finally {
                    starweaveStreamSlots.release();
                }
            });
        } catch (RejectedExecutionException stopped) {
            starweaveStreamSlots.release();
            sendResponse(exchange, 503, "application/json",
                    JSON.toJSONString(apiError("STREAM_STOPPED", "会话流服务正在停止")));
        }
    }

    private void serveStarweaveTeamStream(HttpExchange exchange) throws IOException {
        String teamId = param(exchange, "teamId");
        String teamMemberId = param(exchange, "teamMemberId");
        if (teamId == null || teamId.trim().isEmpty()
                || teamMemberId == null || teamMemberId.trim().isEmpty()) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_REQUEST",
                            "teamId、teamMemberId 均不能为空")));
            return;
        }
        long after = parseLong(param(exchange, "after"), 0L);
        String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (lastEventId != null && !lastEventId.trim().isEmpty()) {
            after = Math.max(after, parseLong(lastEventId, after));
        }

        // Validate Team ownership before committing streaming headers.
        StarweaveTeamApiBridge.events(after, teamId, teamMemberId);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(25);
        try (OutputStream output = exchange.getResponseBody()) {
            writeSseComment(output, "starweave team stream ready");
            while (System.currentTimeMillis() < deadline
                    && !Thread.currentThread().isInterrupted()) {
                com.alibaba.fastjson.JSONArray values = StarweaveTeamApiBridge.awaitEvents(
                        after, teamId, teamMemberId, 10_000L);
                if (values.isEmpty()) {
                    writeSseComment(output, "heartbeat");
                    continue;
                }
                for (int i = 0; i < values.size(); i++) {
                    JSONObject event = values.getJSONObject(i);
                    after = Math.max(after, event.getLongValue("eventSeq"));
                    writeSse(output, Long.toString(event.getLongValue("eventSeq")),
                            "starweave-team", event.toJSONString());
                }
            }
        }
    }

    private void handleStarweaveTeamResources(HttpExchange exchange, String action)
            throws IOException {
        if (!allowStarweaveOrigin(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            JSONObject request = new JSONObject(true);
            request.put("requestId", UUID.randomUUID().toString());
            request.put("teamId", param(exchange, "teamId"));
            request.put("teamMemberId", param(exchange, "teamMemberId"));
            request.put("acpClientId", param(exchange, "acpClientId"));
            request.put("sessionId", param(exchange, "sessionId"));
            request.put("resourceId", param(exchange, "resourceId"));
            if ("download".equals(action)) {
                com.mola.cmd.proxy.app.acp.team.TeamResourcePayload resource =
                        StarweaveTeamApiBridge.downloadResource(request);
                exchange.getResponseHeaders().set("Content-Type", resource.getContentType());
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                String encoded = java.net.URLEncoder.encode(
                        resource.getFileName(), "UTF-8").replace("+", "%20");
                exchange.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename*=UTF-8''" + encoded);
                byte[] bytes = resource.getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
                return;
            }
            JSONObject data = StarweaveTeamApiBridge.resources(
                    request, "preview".equals(action));
            boolean accepted = data.getBooleanValue("accepted");
            sendResponse(exchange, accepted ? 200 : 422, "application/json",
                    JSON.toJSONString(starweaveEnvelope(null, accepted,
                            data.getString("code"), data.getString("message"), data)));
        } catch (Exception e) {
            sendStarweaveError(exchange, null, e);
        }
    }

    private JSONObject executeStarweaveCommand(String action, JSONObject request)
            throws Exception {
            JSONObject data;
            switch (action) {
                case "open":
                    data = StarweaveSessionApiBridge.open(request.getString("robotName"));
                    break;
                case "messages":
                    com.alibaba.fastjson.JSONArray uploadValues =
                            request.getJSONArray("uploadIds");
                    data = StarweaveSessionApiBridge.send(
                            request.getString("groupId"), request.getString("message"),
                            request.getString("expectedSessionId"),
                            request.getLongValue("expectedGeneration"),
                            request.getString("busyPolicy"), uploadValues == null
                                    ? java.util.Collections.emptyList()
                                    : uploadValues.toJavaList(String.class));
                    break;
                case "cancel":
                    data = StarweaveSessionApiBridge.cancel(
                            request.getString("groupId"),
                            request.getString("expectedSessionId"),
                            request.getLongValue("expectedGeneration"));
                    break;
                case "new":
                    data = StarweaveSessionApiBridge.newSession(
                            request.getString("groupId"),
                            request.getString("expectedSessionId"),
                            request.getLongValue("expectedGeneration"));
                    break;
                case "restore":
                    data = StarweaveSessionApiBridge.restore(
                            request.getString("groupId"),
                            request.getString("targetSessionId"),
                            request.getString("expectedSessionId"),
                            request.getLongValue("expectedGeneration"));
                    break;
                case "delete":
                    data = StarweaveSessionApiBridge.delete(
                            request.getString("groupId"),
                            request.getString("expectedSessionId"),
                            request.getLongValue("expectedGeneration"));
                    break;
                default:
                    throw new IllegalArgumentException("unsupported action: " + action);
            }
            return data;
    }

    private boolean allowStarweaveOrigin(HttpExchange exchange) throws IOException {
        String fetchSite = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if ("cross-site".equalsIgnoreCase(fetchSite)) {
            sendResponse(exchange, 403, "application/json",
                    JSON.toJSONString(apiError("ORIGIN_REJECTED", "不允许跨站访问会话接口")));
            return false;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.trim().isEmpty()) return true;
        String host = exchange.getRequestHeaders().getFirst("Host");
        try {
            java.net.URI uri = java.net.URI.create(origin);
            String originAuthority = uri.getRawAuthority();
            if (("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    && originAuthority != null && host != null
                    && originAuthority.equalsIgnoreCase(host.trim())) return true;
        } catch (RuntimeException ignored) { }
        sendResponse(exchange, 403, "application/json",
                JSON.toJSONString(apiError("ORIGIN_REJECTED", "会话接口仅接受同源请求")));
        return false;
    }

    private JSONObject starweaveEnvelope(String requestId, boolean accepted,
                                         String code, String message,
                                         Object data) {
        JSONObject result = new JSONObject(true);
        result.put("schemaVersion", 1);
        result.put("requestId", requestId == null || requestId.trim().isEmpty()
                ? UUID.randomUUID().toString() : requestId);
        result.put("accepted", accepted);
        result.put("code", code);
        result.put("message", message == null ? "" : message);
        result.put("data", data);
        return result;
    }

    JSONObject starweaveCommandEnvelope(String requestId, JSONObject data) {
        boolean accepted = !data.containsKey("accepted")
                || data.getBooleanValue("accepted");
        String code = data.getString("code");
        String message = data.getString("message");
        return starweaveEnvelope(requestId, accepted,
                code == null ? (accepted ? "OK" : "REJECTED_STATE") : code,
                message == null ? "" : message, data);
    }

    private void sendStarweaveError(HttpExchange exchange, String requestId,
                                    Exception error) throws IOException {
        String message = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        String code;
        if (message.startsWith("SESSION_STALE")) code = "SESSION_STALE";
        else if (lower.startsWith("robot not found")) code = "ROBOT_NOT_FOUND";
        else if (lower.startsWith("robot is disabled")) code = "ROBOT_DISABLED";
        else if (lower.contains("cannot create a main session")) code = "ROBOT_NOT_MAIN_CAPABLE";
        else if (lower.startsWith("session not found")) code = "SESSION_NOT_FOUND";
        else if (lower.contains("not ready")) code = "STATE_NOT_READY";
        else if (lower.contains("busy session")) code = "ALREADY_BUSY";
        else if (lower.contains("restore") && lower.contains("not support"))
            code = "PROVIDER_RESTORE_UNSUPPORTED";
        else if (lower.contains("upload not found") || lower.contains("upload has expired"))
            code = "UPLOAD_NOT_FOUND";
        else if (lower.contains("file size") || lower.contains("file too large"))
            code = "FILE_TOO_LARGE";
        else if (lower.contains("resource not found") || lower.contains("outside workspace"))
            code = "PATH_OUTSIDE_WORKSPACE";
        else if (lower.contains("service is not running")) code = "SERVICE_UNAVAILABLE";
        else if (error instanceof IllegalArgumentException) code = "INVALID_REQUEST";
        else code = "INTERNAL_ERROR";
        int status = "SESSION_STALE".equals(code) || "STATE_NOT_READY".equals(code)
                || "ALREADY_BUSY".equals(code) ? 409
                : "ROBOT_NOT_FOUND".equals(code) || "SESSION_NOT_FOUND".equals(code)
                || "UPLOAD_NOT_FOUND".equals(code) ? 404
                : "FILE_TOO_LARGE".equals(code) ? 413
                : "SERVICE_UNAVAILABLE".equals(code) ? 503
                : "INTERNAL_ERROR".equals(code) ? 500 : 400;
        sendResponse(exchange, status, "application/json",
                JSON.toJSONString(starweaveEnvelope(
                        requestId, false, code, message, null)));
    }

    private static long parseLong(String value, long defaultValue) {
        if (value == null || value.trim().isEmpty()) return defaultValue;
        try { return Long.parseLong(value.trim()); }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid long value: " + value);
        }
    }

    private void handleTeamSharingStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        JSONObject result = new JSONObject();
        result.put("instanceId", CmdProxyHome.instanceId());
        result.put("grants", TeamSharingStatusRegistry.snapshot());
        sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
    }

    private void handleChannelInbound(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            JSONObject request = JSON.parseObject(readBody(exchange));
            String channelId = request == null ? null : request.getString("channelId");
            if (isBlank(channelId) || !request.containsKey("inboundEnabled")) {
                sendResponse(exchange, 400, "application/json",
                        "{\"error\":\"channelId and inboundEnabled are required\"}");
                return;
            }
            boolean enabled = request.getBooleanValue("inboundEnabled");
            boolean previous = channelInboundUpdater.apply(channelId.trim(), enabled);
            try {
                ChannelConfigFileStore.setInboundEnabled(channelId.trim(), enabled);
            } catch (Exception persistFailure) {
                try {
                    channelInboundUpdater.apply(channelId.trim(), previous);
                } catch (Exception rollbackFailure) {
                    persistFailure.addSuppressed(rollbackFailure);
                }
                throw persistFailure;
            }
            sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
        } catch (Exception e) {
            logger.error("切换信道入站状态失败", e);
            JSONObject error = new JSONObject();
            error.put("error", e.getMessage() == null ? "unknown error" : e.getMessage());
            sendResponse(exchange, 500, "application/json", JSON.toJSONString(error));
        }
    }

    private void handleChannelPrivateChat(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            JSONObject request = JSON.parseObject(readBody(exchange));
            String channelId = request == null ? null : request.getString("channelId");
            if (isBlank(channelId) || !request.containsKey("privateChatEnabled")) {
                sendResponse(exchange, 400, "application/json",
                        "{\"error\":\"channelId and privateChatEnabled are required\"}");
                return;
            }
            boolean enabled = request.getBooleanValue("privateChatEnabled");
            boolean previous = channelPrivateChatUpdater.apply(channelId.trim(), enabled);
            try {
                ChannelConfigFileStore.setPrivateChatEnabled(channelId.trim(), enabled);
            } catch (Exception persistFailure) {
                try {
                    channelPrivateChatUpdater.apply(channelId.trim(), previous);
                } catch (Exception rollbackFailure) {
                    persistFailure.addSuppressed(rollbackFailure);
                }
                throw persistFailure;
            }
            sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
        } catch (Exception e) {
            logger.error("切换信道单聊状态失败", e);
            JSONObject error = new JSONObject();
            error.put("error", e.getMessage() == null ? "unknown error" : e.getMessage());
            sendResponse(exchange, 500, "application/json", JSON.toJSONString(error));
        }
    }

    private void handleChannelBindingTargets(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        JSONObject result = new JSONObject();
        result.put("instanceId", CmdProxyHome.instanceId());
        result.put("sessions", StarweaveSessionApiBridge.list());
        try {
            List<Map<String, Object>> teams = channelBindingTargetSupplier.get();
            result.put("teams", teams == null ? java.util.Collections.emptyList() : teams);
        } catch (RuntimeException e) {
            logger.warn("读取信道 Team 绑定目标失败", e);
            result.put("teams", java.util.Collections.emptyList());
        }
        sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
    }

    private void maskChannelSecrets(JSONObject json) {
        com.alibaba.fastjson.JSONArray channels = json.getJSONArray("channels");
        if (channels == null) return;
        for (int i = 0; i < channels.size(); i++) {
            JSONObject channel = channels.getJSONObject(i);
            if (channel != null && !isBlank(channel.getString("secret"))) {
                channel.put("secret", SECRET_MASK);
            }
        }
    }

    /** Mask or blank means keep the prior secret for the same channel id. */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
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

    private void handleRefreshRobot(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            String body = readBody(exchange);
            JSONObject json = JSON.parseObject(body);
            String name = json.getString("name");
            if (name == null || name.trim().isEmpty()) {
                sendResponse(exchange, 400, "application/json", "{\"error\":\"name 不能为空\"}");
                return;
            }
            refreshRobotCallback.accept(name.trim());
            sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
        } catch (Exception e) {
            logger.error("按 robot 刷新服务失败", e);
            sendResponse(exchange, 500, "application/json",
                    "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    private void handleRefreshChannel(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            JSONObject json = JSON.parseObject(readBody(exchange));
            String channelId = json == null ? null : json.getString("channelId");
            String previousChannelId = json == null ? null : json.getString("previousChannelId");
            String current = channelId == null ? "" : channelId.trim();
            String previous = previousChannelId == null ? "" : previousChannelId.trim();
            if (current.isEmpty() && previous.isEmpty()) {
                sendResponse(exchange, 400, "application/json",
                        "{\"error\":\"channelId 不能为空\"}");
                return;
            }
            refreshChannelCallback.accept(previous, current);
            sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
        } catch (Exception e) {
            logger.error("按 channel 刷新服务失败", e);
            String message = e.getMessage() == null ? "unknown error" : e.getMessage();
            sendResponse(exchange, 500, "application/json",
                    "{\"error\":\"" + message.replace("\"", "'") + "\"}");
        }
    }

    /**
     * 目录浏览 API：GET /api/browse-dir?path=/some/path
     * 返回规范化路径、父目录、文件系统根目录和完整子目录路径。
     * 路径拼接全部在服务端完成，以兼容 Windows 盘符、反斜杠和 UNC 路径。
     */
    private void handleBrowseDir(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String dirPath = param(exchange, "path");
        if (dirPath == null || dirPath.trim().isEmpty()) {
            dirPath = System.getProperty("user.home");
        }

        File dir = Paths.get(dirPath).toAbsolutePath().normalize().toFile();
        JSONObject result = new JSONObject();
        result.put("path", dir.getPath());
        result.put("parent", dir.getParent());
        result.put("roots", directoryEntries(File.listRoots()));

        if (!dir.exists() || !dir.isDirectory()) {
            result.put("dirs", new ArrayList<>());
            result.put("error", "not a directory");
            sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
            return;
        }

        File[] children = dir.listFiles(File::isDirectory);
        if (children != null) {
            java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        }
        result.put("dirs", directoryEntries(children));
        sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
    }

    private void handleAgentResourceTree(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            AcpRobotParam robot = findConfiguredRobot(param(exchange, "robot"));
            JSONObject result = agentResourceBrowser.tree(robot, param(exchange, "kind"));
            sendResponse(exchange, 200, "application/json", JSON.toJSONString(result));
        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_RESOURCE_REQUEST", e.getMessage())));
        } catch (Exception e) {
            logger.warn("读取智能体资源树失败", e);
            sendResponse(exchange, 500, "application/json",
                    JSON.toJSONString(apiError("RESOURCE_TREE_FAILED", e.getMessage())));
        }
    }

    private void handleAgentResourceContent(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            AcpRobotParam robot = findConfiguredRobot(param(exchange, "robot"));
            JSONObject result = agentResourceBrowser.content(robot,
                    param(exchange, "kind"), param(exchange, "resource"));
            int status = result.getBooleanValue("ok") ? 200 : 422;
            sendResponse(exchange, status, "application/json", JSON.toJSONString(result));
        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, "application/json",
                    JSON.toJSONString(apiError("INVALID_RESOURCE_REQUEST", e.getMessage())));
        } catch (Exception e) {
            logger.warn("读取智能体资源内容失败", e);
            sendResponse(exchange, 500, "application/json",
                    JSON.toJSONString(apiError("RESOURCE_READ_FAILED", e.getMessage())));
        }
    }

    private AcpRobotParam findConfiguredRobot(String robotName) throws IOException {
        if (robotName == null || robotName.trim().isEmpty()) {
            throw new IllegalArgumentException("robot is required");
        }
        Path configPath = Paths.get(CONFIG_PATH);
        if (!Files.isRegularFile(configPath)) {
            throw new IllegalArgumentException("agent configuration does not exist");
        }
        JSONObject config = JSON.parseObject(new String(
                Files.readAllBytes(configPath), StandardCharsets.UTF_8));
        com.alibaba.fastjson.JSONArray robots = config.getJSONArray("robots");
        if (robots != null) {
            for (int i = 0; i < robots.size(); i++) {
                JSONObject item = robots.getJSONObject(i);
                if (item != null && robotName.equals(item.getString("name"))) {
                    return item.toJavaObject(AcpRobotParam.class);
                }
            }
        }
        throw new IllegalArgumentException("agent not found: " + robotName);
    }

    private List<JSONObject> directoryEntries(File[] directories) {
        List<JSONObject> result = new ArrayList<>();
        if (directories == null) {
            return result;
        }
        for (File directory : directories) {
            JSONObject entry = new JSONObject();
            String path = directory.toPath().toAbsolutePath().normalize().toString();
            String name = directory.getName();
            entry.put("path", path);
            entry.put("name", name == null || name.isEmpty() ? path : name);
            result.add(entry);
        }
        return result;
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

    private String readBody(HttpExchange exchange, long maxBytes) throws IOException {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null && parseLong(contentLength, -1L) > maxBytes) {
            throw new IllegalArgumentException("request body is too large");
        }
        try (InputStream input = exchange.getRequestBody();
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            long total = 0L;
            int length;
            while ((length = input.read(chunk)) >= 0) {
                total += length;
                if (total > maxBytes) {
                    throw new IllegalArgumentException("request body is too large");
                }
                buffer.write(chunk, 0, length);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
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

            String updateJarUrl = "https://" + CmdProxyConf.INSTANCE.getRemoteHost()
                    + "/download/cmd-proxy.jar";
            logger.info("开始下载更新: {} -> {}", updateJarUrl, jarPath);

            // 创建忽略 SSL 的连接
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);

            URL url = new URL(updateJarUrl);
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
