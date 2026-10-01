package com.mola.cmd.proxy.app.acp.registry;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.common.InstanceRegistry;
import com.mola.cmd.proxy.app.acp.registry.model.RegistryConfig;
import com.mola.cmd.proxy.app.acp.registry.tunnel.*;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 独立生命周期：中心只寻址与转发，客户端只注册当前进程。 */
public final class RegistryManager implements AutoCloseable {
    public static final String MASK = "********";
    private final RegistryConfigStore store;
    private final RemoteEnvironmentRegistry environments;
    private final TunnelProvider tunnel;
    private final RegistryClient client = new RegistryClient();
    private final int localPort;
    private final String localInstanceId;
    private final String pluginPath = "/api/registry/frp/" + UUID.randomUUID();
    private final String tunnelToken = UUID.randomUUID().toString() + UUID.randomUUID();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "environment-registry"); t.setDaemon(true); return t; });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor probes = new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128), r -> { Thread t = new Thread(r, "registry-probe"); t.setDaemon(true); return t; });
    private final Set<String> verifying = ConcurrentHashMap.newKeySet();
    private com.sun.net.httpserver.HttpServer pluginServer;
    private ExecutorService pluginExecutor;
    private volatile RegistryConfig config;
    private volatile String serverStatus = "STOPPED", clientStatus = "UNREGISTERED", serverError = "", clientError = "";
    private volatile JSONObject connection;
    private String serverSignature = "", clientSignature = "";
    private boolean serverAttempted;
    private volatile boolean serverReady;
    private final Thread shutdownHook = new Thread(this::close, "registry-shutdown");

    public RegistryManager(Path directory, int localPort, String localInstanceId) throws IOException {
        this(directory, localPort, localInstanceId, new FrpTunnelProvider(directory));
    }
    public RegistryManager(Path directory, int localPort, String localInstanceId, TunnelProvider tunnel) throws IOException {
        this.store = new RegistryConfigStore(directory); this.localPort = localPort; this.localInstanceId = localInstanceId; this.tunnel = tunnel;
        config = store.load(); environments = new RemoteEnvironmentRegistry(store);
    }
    public void start() { start(10_000); }
    void start(long intervalMillis) {
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        worker.scheduleWithFixedDelay(this::tickSafely, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }
    public List<InstanceRegistry.InstanceInfo> environments() { return environments.list(); }
    public int resolve(String instanceId) { return environments.resolve(instanceId); }
    public synchronized JSONObject settings() {
        JSONObject result = (JSONObject) JSON.toJSON(config);
        result.put("serverCredential", config.serverCredential.isEmpty() ? "" : MASK);
        result.put("clientCredential", config.clientCredential.isEmpty() ? "" : MASK);
        result.remove("nodeId"); result.put("serverStatus", serverStatus); result.put("clientStatus", clientStatus);
        result.put("serverError", serverError); result.put("clientError", clientError);
        result.put("frpVersion", FrpRuntimeInstaller.VERSION);
        return result;
    }
    public synchronized JSONObject configure(JSONObject input) throws IOException {
        if (closed.get()) throw new IOException("注册服务已停止");
        RegistryConfig next = JSON.parseObject(JSON.toJSONString(config), RegistryConfig.class);
        if (input.containsKey("serverEnabled")) next.serverEnabled = input.getBooleanValue("serverEnabled");
        if (input.containsKey("tunnelPort")) next.tunnelPort = input.getIntValue("tunnelPort");
        if (input.containsKey("serverCredential") && !MASK.equals(input.getString("serverCredential"))) next.serverCredential = text(input, "serverCredential");
        if (next.serverEnabled && next.serverCredential.isEmpty()) next.serverCredential = UUID.randomUUID().toString() + UUID.randomUUID();
        if (input.containsKey("clientEnabled")) next.clientEnabled = input.getBooleanValue("clientEnabled");
        if (input.containsKey("centerUrl")) next.centerUrl = text(input, "centerUrl");
        if (input.containsKey("clientCredential") && !MASK.equals(input.getString("clientCredential"))) next.clientCredential = text(input, "clientCredential");
        if (input.containsKey("displayName")) next.displayName = text(input, "displayName");
        next.validate(); store.save(next); config = next;
        serverStatus = next.serverEnabled ? "STARTING" : "STOPPED";
        clientStatus = next.clientEnabled ? "CONNECTING" : "UNREGISTERED";
        worker.execute(this::tickSafely);
        return settings();
    }
    public void handleControl(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String action = path.substring("/api/registry/".length());
        try {
            if ("identity".equals(action)) {
                if (!"GET".equals(exchange.getRequestMethod())) { respond(exchange, 405, error("Method Not Allowed")); return; }
                JSONObject active = connection;
                if (active == null || !RemoteEnvironmentRegistry.equal(active.getString("lease"), exchange.getRequestHeaders().getFirst("X-Starweave-Registry-Lease"))) {
                    respond(exchange, 401, error("注册身份校验失败")); return;
                }
                JSONObject identity = new JSONObject(); identity.put("instanceId", localInstanceId); identity.put("nodeId", config.nodeId);
                respond(exchange, 200, identity); return;
            }

            RegistryConfig snapshot = config;
            String supplied = exchange.getRequestHeaders().getFirst("Authorization");
            if (!snapshot.serverEnabled || !RemoteEnvironmentRegistry.equal("Bearer " + snapshot.serverCredential, supplied)) {
                respond(exchange, 401, error("中心未启用或注册凭证无效")); return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) { respond(exchange, 405, error("Method Not Allowed")); return; }
            if (!Arrays.asList("register", "heartbeat", "unregister").contains(action)) { respond(exchange, 404, error("接口不存在")); return; }
            JSONObject input = JSON.parseObject(RegistryClient.read(exchange.getRequestBody()));
            if (input == null) throw new IllegalArgumentException("请求不能为空");
            JSONObject result = new JSONObject();
            if ("register".equals(action)) {
                if (!serverReady || !tunnel.serverAlive()) { respond(exchange, 503, error("中心隧道尚未就绪")); return; }
                RemoteEnvironmentRegistry.Entry entry = environments.register(input.getString("nodeId"), input.getString("instanceId"), text(input, "displayName"));
                result.put("environmentId", entry.id); result.put("lease", entry.lease); result.put("remotePort", entry.port);
                result.put("tunnelPort", snapshot.tunnelPort); result.put("tunnelToken", tunnelToken); result.put("online", entry.online);
            } else if ("heartbeat".equals(action)) {
                RemoteEnvironmentRegistry.Entry entry = environments.heartbeat(input.getString("environmentId"), input.getString("lease"));
                result.put("online", entry.online);
            } else { environments.unregister(input.getString("environmentId"), input.getString("lease")); result.put("success", true); }
            respond(exchange, 200, result);
        } catch (IllegalArgumentException e) { respond(exchange, 400, error(e.getMessage())); }
        catch (IOException e) { respond(exchange, 503, error("注册服务请求失败")); }
    }
    public void handleAdmin(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("/api/registry/settings/credential".equals(path)) {
                if (!"POST".equals(exchange.getRequestMethod())) { respond(exchange, 405, error("Method Not Allowed")); return; }
                JSONObject result = new JSONObject(); result.put("credential", config.serverCredential);
                exchange.getResponseHeaders().set("Cache-Control", "no-store"); respond(exchange, 200, result); return;
            }
            if (!"/api/registry/settings".equals(path)) { respond(exchange, 404, error("接口不存在")); return; }
            if ("GET".equals(exchange.getRequestMethod())) respond(exchange, 200, settings());
            else if ("POST".equals(exchange.getRequestMethod())) {
                JSONObject input = JSON.parseObject(RegistryClient.read(exchange.getRequestBody()));
                if (input == null) throw new IllegalArgumentException("配置不能为空");
                respond(exchange, 202, configure(input));
            } else respond(exchange, 405, error("Method Not Allowed"));
        } catch (IllegalArgumentException e) { respond(exchange, 400, error(e.getMessage())); }
        catch (IOException e) { respond(exchange, 503, error("注册配置保存失败")); }
    }
    private void handlePlugin(HttpExchange exchange) throws IOException {
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress() || !"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 403, error("Forbidden")); return;
        }
        JSONObject input = JSON.parseObject(RegistryClient.read(exchange.getRequestBody()));
        String query = exchange.getRequestURI().getRawQuery();
        String op = "";
        if (query != null) for (String pair : query.split("&")) if (pair.startsWith("op=")) op = URLDecoder.decode(pair.substring(3), "UTF-8");
        boolean allowed = config.serverEnabled && input != null && input.getJSONObject("content") != null
                && environments.pluginAllowed(op, input.getJSONObject("content"));
        JSONObject result = new JSONObject(); result.put("reject", !allowed); result.put("unchange", true);
        if (!allowed) result.put("reject_reason", "unregistered environment or invalid proxy");
        respond(exchange, 200, result);
    }
    private void tickSafely() {
        if (closed.get()) return;
        RegistryConfig snapshot = config;
        try { reconcileServer(snapshot); }
        catch (Exception e) { serverReady = false; serverStatus = "ERROR"; serverError = safeError(e); environments.clearOnline(); }
        if (closed.get()) return;
        try { reconcileClient(snapshot); }
        catch (Exception e) { clientStatus = "ERROR"; clientError = redact(safeError(e), snapshot.clientCredential); }
        if (serverReady) for (RemoteEnvironmentRegistry.Entry entry : environments.candidates()) {
            if (closed.get()) break;
            if (!verifying.add(entry.id)) continue;
            try { probes.execute(() -> {
                try { environments.verified(entry.id, entry.lease, entry.runId, verify(entry)); }
                finally { verifying.remove(entry.id); }
            }); } catch (RejectedExecutionException e) { verifying.remove(entry.id); }
        }
    }
    private void reconcileServer(RegistryConfig snapshot) throws IOException {
        String signature = snapshot.serverEnabled + ":" + snapshot.tunnelPort + ":" + snapshot.serverCredential;
        if (!signature.equals(serverSignature)) {
            tunnel.stopServer(); serverReady = false; environments.clearOnline(); serverSignature = signature; serverAttempted = false;
        }
        if (!snapshot.serverEnabled) { serverStatus = "STOPPED"; serverError = ""; return; }
        if (!tunnel.serverAlive()) {
            serverStatus = serverAttempted ? "ERROR" : "STARTING";
            serverError = serverAttempted ? "中心隧道未启动，请检查监听端口和 frp 日志" : "";
            serverAttempted = true;
            tunnel.startServer(snapshot.tunnelPort, tunnelToken, pluginAddress(), pluginPath);
        }
        if (closed.get() || snapshot != config) { tunnel.stopServer(); serverReady = false; return; }
        serverReady = tunnel.serverAlive() && reachable(snapshot.tunnelPort);
        if (serverReady) { serverStatus = "RUNNING"; serverError = ""; }
    }
    private synchronized String pluginAddress() throws IOException {
        if (closed.get()) throw new IOException("注册服务已停止");
        if (pluginServer == null) {
            pluginServer = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            pluginExecutor = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "registry-frp-callback"); t.setDaemon(true); return t; });
            pluginServer.setExecutor(pluginExecutor);
            pluginServer.createContext(pluginPath, this::handlePlugin);
            pluginServer.start();
        }
        return "127.0.0.1:" + pluginServer.getAddress().getPort();
    }

    private void reconcileClient(RegistryConfig snapshot) throws IOException {
        String signature = snapshot.clientEnabled + ":" + snapshot.centerUrl + ":" + snapshot.clientCredential + ":" + snapshot.displayName;
        if (!signature.equals(clientSignature)) {
            tunnel.stopClient(); JSONObject old = connection; connection = null;
            if (old != null) {
                try { client.request(old.getString("centerUrl"), "unregister", old.getString("credential"), leaseBody(old)); }
                catch (IOException ignored) { }
            }
            clientSignature = signature;
        }
        if (!snapshot.clientEnabled) { clientStatus = "UNREGISTERED"; clientError = ""; return; }
        if (connection == null) {
            clientStatus = "CONNECTING";
            JSONObject request = new JSONObject(); request.put("nodeId", snapshot.nodeId); request.put("instanceId", localInstanceId);
            request.put("displayName", snapshot.displayName.isEmpty() ? localInstanceId : snapshot.displayName);
            JSONObject result = client.request(snapshot.centerUrl, "register", snapshot.clientCredential, request);
            validateConnection(result);
            if (closed.get() || snapshot != config) {
                try { client.request(snapshot.centerUrl, "unregister", snapshot.clientCredential, leaseBody(result)); }
                catch (IOException ignored) { }
                return;
            }
            result.put("centerUrl", snapshot.centerUrl); result.put("credential", snapshot.clientCredential); result.put("connectedAt", System.currentTimeMillis());
            connection = result;
        }
        JSONObject active = connection;
        try {
            JSONObject status = client.request(snapshot.centerUrl, "heartbeat", snapshot.clientCredential, leaseBody(active));
            if (!tunnel.clientAlive()) {
                clientStatus = "CONNECTING";
                tunnel.startClient(URI.create(snapshot.centerUrl).getHost(), active.getIntValue("tunnelPort"), active.getString("tunnelToken"),
                        active.getString("environmentId"), active.getString("lease"), active.getIntValue("remotePort"), localPort);
                if (closed.get() || snapshot != config) tunnel.stopClient();
                clientStatus = "CONNECTING"; clientError = ""; return;
            }
            boolean online = status.getBooleanValue("online");
            boolean overdue = !online && System.currentTimeMillis() - active.getLongValue("connectedAt") > 60_000;
            clientStatus = online ? "REGISTERED" : overdue ? "ERROR" : "CONNECTING";
            clientError = overdue ? "环境已登记，但隧道尚未连通，请检查中心的隧道端口" : "";
        } catch (IOException e) {
            // 中心重启或租约失效：关闭旧隧道，下次重新登记；不重放业务请求。
            tunnel.stopClient(); connection = null; throw e;
        }
    }
    private static void validateConnection(JSONObject result) throws IOException {
        String id = result.getString("environmentId"), lease = result.getString("lease");
        if (id == null || !id.matches("remote-[a-f0-9-]{36}") || lease == null || lease.length() != 36
                || result.getString("tunnelToken") == null || result.getIntValue("tunnelPort") < 1 || result.getIntValue("tunnelPort") > 65535
                || result.getIntValue("remotePort") < 20000 || result.getIntValue("remotePort") > 29999) throw new IOException("中心返回了无效的隧道参数");
    }
    private boolean verify(RemoteEnvironmentRegistry.Entry entry) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:" + entry.port + "/api/registry/identity").openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(1500); connection.setReadTimeout(1500);
            connection.setRequestProperty("X-Starweave-Registry-Lease", entry.lease);
            if (connection.getResponseCode() != 200) return false;
            JSONObject identity = JSON.parseObject(RegistryClient.read(connection.getInputStream()));
            return identity != null && entry.sourceInstanceId.equals(identity.getString("instanceId")) && entry.nodeId.equals(identity.getString("nodeId"));
        } catch (Exception e) { return false; }
        finally { if (connection != null) connection.disconnect(); }
    }
    private static boolean reachable(int port) {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 500); return true; }
        catch (IOException e) { return false; }
    }
    private static JSONObject leaseBody(JSONObject active) { JSONObject result = new JSONObject(); result.put("environmentId", active.getString("environmentId")); result.put("lease", active.getString("lease")); return result; }
    private static String text(JSONObject input, String key) { String value = input.getString(key); return value == null ? "" : value.trim(); }
    private static String redact(String message, String credential) {
        return credential == null || credential.isEmpty() ? message : message.replace(credential, "[已隐藏]");
    }
    private static String safeError(Exception e) {
        // 不回显 HTTP 请求头、配置文本或 frp 原始日志中的凭证。
        if (e instanceof java.net.ConnectException) return "无法连接服务器，请检查地址与端口";
        if (e instanceof java.net.SocketTimeoutException) return "服务器连接超时";
        String message = e.getMessage();
        return message != null && message.length() <= 160 ? message : "注册连接失败，请检查服务器与网络";
    }
    private static JSONObject error(String message) { JSONObject result = new JSONObject(); result.put("error", message); return result; }
    private static void respond(HttpExchange exchange, int status, JSONObject result) throws IOException {
        byte[] bytes = result.toJSONString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        serverReady = false; worker.shutdownNow(); probes.shutdownNow(); tunnel.close(); environments.clearOnline();
        synchronized (this) { if (pluginServer != null) pluginServer.stop(0); if (pluginExecutor != null) pluginExecutor.shutdownNow(); }
        try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
        catch (IllegalStateException ignored) { }
    }
}
