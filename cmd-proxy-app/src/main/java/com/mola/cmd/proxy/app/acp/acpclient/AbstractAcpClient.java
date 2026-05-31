package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.agent.KiroCliAgentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ACP Client 抽象基类，封装子进程管理和 ACP 协议通信层。
 * <p>
 * 提供通用能力：
 * <ul>
 *   <li>子进程启动与 PATH 配置</li>
 *   <li>ACP initialize 协议握手</li>
 *   <li>JSON-RPC 2.0 request/response 收发</li>
 * </ul>
 * <p>
 * 子类通过实现 {@link #createSession()} 来定制 session/new 的参数（如是否加载 MCP）。
 */
public abstract class AbstractAcpClient implements Closeable {

    /**
     * AcpClient 生命周期状态
     */
    public enum State {
        CREATED, STARTING, READY, BUSY, ERROR, CLOSED
    }

    private static final Logger logger = LoggerFactory.getLogger(AbstractAcpClient.class);
    protected static final String JSONRPC_VERSION = "2.0";
    private static final int PROTOCOL_VERSION = 1;
    private static final String CLIENT_NAME = "cmd-proxy-acp";
    private static final String CLIENT_VERSION = "1.0.0";

    protected final AgentProvider agentProvider;
    protected final String workspacePath;
    protected final String groupId;
    protected final AcpRobotParam robotParamRef;
    protected final Gson gson = new GsonBuilder().create();
    protected final AtomicInteger idCounter = new AtomicInteger(0);
    protected final AtomicReference<State> state = new AtomicReference<>(State.CREATED);

    protected Process process;
    protected BufferedWriter writer;
    protected BufferedReader reader;
    protected String sessionId;
    protected volatile double contextUsagePercentage = -1;

    /**
     * 使用指定 AgentProvider 创建（protected，供子类使用）。
     */
    protected AbstractAcpClient(AgentProvider agentProvider, String workspacePath, String groupId) {
        this(agentProvider, workspacePath, groupId, null);
    }

    /**
     * 使用指定 AgentProvider 和 robotParam 创建（protected，供子类使用）。
     */
    protected AbstractAcpClient(AgentProvider agentProvider, String workspacePath, String groupId, AcpRobotParam robotParam) {
        this.agentProvider = agentProvider;
        this.workspacePath = (workspacePath == null || workspacePath.trim().isEmpty())
                ? System.getProperty("user.home")
                : workspacePath;
        this.groupId = groupId;
        this.robotParamRef = robotParam;
    }

    /**
     * 使用默认 KiroCliAgentProvider 创建。
     */
    public AbstractAcpClient(String workspacePath, String groupId) {
        this(new KiroCliAgentProvider(), workspacePath, groupId);
    }

    // ==================== 生命周期（模板方法） ====================

    /**
     * 启动 ACP Client：启动子进程 → initialize（带重试）→ createSession
     */
    public void start() throws IOException {
        state.set(State.STARTING);
        try {
            startProcess();
            initializeWithRetry();
            createSession();
            state.set(State.READY);
            logger.info("ACP Client 就绪，sessionId={}, groupId={}", sessionId, groupId);
        } catch (IOException e) {
            state.set(State.ERROR);
            throw e;
        }
    }

    /**
     * 带重试的 initialize，应对子进程启动慢的场景（Windows 下 .cmd 包装、npx 首次下载等）。
     */
    private void initializeWithRetry() throws IOException {
        int maxRetries = 5;
        for (int i = 0; i < maxRetries; i++) {
            // 检查进程是否还活着
            if (process != null && !process.isAlive()) {
                int exitCode = process.exitValue();
                throw new IOException("ACP 子进程已退出 (exitCode=" + exitCode + ")，无法 initialize。请检查 [ACP STDERR] 日志获取崩溃原因。");
            }
            try {
                initialize();
                return;
            } catch (IOException e) {
                if (i == maxRetries - 1) {
                    throw e;
                }
                logger.warn("ACP initialize 失败 (第 {} 次重试): {}", i + 1, e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("initialize 被中断", ie);
                }
            }
        }
    }

    /**
     * 子类实现各自的 session/new 逻辑（如是否加载 MCP servers）。
     */
    protected abstract void createSession() throws IOException;

    @Override
    public void close() throws IOException {
        state.set(State.CLOSED);
        logger.info("关闭 AbstractAcpClient, groupId={}", groupId);
        try { if (writer != null) writer.close(); } catch (IOException e) { /* ignore */ }
        try { if (reader != null) reader.close(); } catch (IOException e) { /* ignore */ }
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }

    // ==================== 协议步骤 ====================

    protected void startProcess() throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(agentProvider.getCommand());
        cmd.addAll(Arrays.asList(agentProvider.getArgs()));

        // 追加 provider 特定的额外参数（如 --model）
        List<String> extraArgs = agentProvider.getExtraArgs(robotParamRef);
        if (extraArgs != null && !extraArgs.isEmpty()) {
            cmd.addAll(extraArgs);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);

        String home = System.getProperty("user.home");
        String currentPath = pb.environment().getOrDefault("PATH", "");
        String extraPaths = home + "/.local/bin"
                + File.pathSeparator + home + "/.cargo/bin"
                + File.pathSeparator + "/usr/local/bin";
        // Windows: 进程可能从精简环境启动（如服务/计划任务），PATH 不完整，从注册表读取完整 PATH
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            String regPath = readWindowsRegistryPath();
            if (regPath != null && !regPath.isEmpty()) {
                currentPath = regPath;
            }
        }
        if (!currentPath.contains(home + "/.local/bin")) {
            pb.environment().put("PATH", extraPaths + File.pathSeparator + currentPath);
        }

        // 追加 provider 特定的额外环境变量（如 OPENCODE_CONFIG_CONTENT）
        Map<String, String> extraEnv = agentProvider.getExtraEnv(robotParamRef);
        if (extraEnv != null && !extraEnv.isEmpty()) {
            pb.environment().putAll(extraEnv);
        }

        // 按 robot 维度注入 HTTP 代理环境变量
        if (robotParamRef != null && robotParamRef.isProxyEnabled()) {
            String proxy = robotParamRef.getHttpProxy();
            if (proxy != null && !proxy.trim().isEmpty()) {
                String url = proxy.contains("://") ? proxy.trim() : "http://" + proxy.trim();
                pb.environment().put("HTTP_PROXY", url);
                pb.environment().put("http_proxy", url);
                pb.environment().put("HTTPS_PROXY", url);
                pb.environment().put("https_proxy", url);
            }
            String noProxy = robotParamRef.getNoProxy();
            if (noProxy != null && !noProxy.trim().isEmpty()) {
                pb.environment().put("NO_PROXY", noProxy.trim());
                pb.environment().put("no_proxy", noProxy.trim());
            }
        }

        logger.info("启动 ACP 进程: {}, PATH contains node: {}", cmd,
                pb.environment().getOrDefault("PATH", "").contains("node"));
        process = pb.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        // stderr 日志转发（用 INFO 级别，方便排查子进程崩溃原因）
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = errReader.readLine()) != null) {
                    logger.info("[ACP STDERR][{}] {}", groupId, line);
                }
            } catch (IOException e) {
                // 进程关闭时正常退出
            }
            // 进程退出时记录 exit code
            if (process != null) {
                try {
                    int exitCode = process.waitFor();
                    logger.warn("[ACP STDERR][{}] 进程已退出, exitCode={}", groupId, exitCode);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "acp-stderr-" + groupId);
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    protected void initialize() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);

        JsonObject capabilities = new JsonObject();
        params.add("clientCapabilities", capabilities);

        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", CLIENT_NAME);
        clientInfo.addProperty("version", CLIENT_VERSION);
        params.add("clientInfo", clientInfo);

        JsonObject response = sendRequest("initialize", params);
        JsonObject result = response.getAsJsonObject("result");
        logger.info("ACP initialize 完成: {}", result);
    }

    // ==================== JSON-RPC 工具方法 ====================

    protected JsonObject sendRequest(String method, JsonObject params) throws IOException {
        JsonObject request = buildRequest(method, params);
        String id = request.get("id").getAsString();
        sendJson(request);

        while (true) {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("ACP 进程意外关闭");
            }
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) continue;

            JsonObject resp;
            try {
                resp = JsonParser.parseString(trimmed).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                continue;
            }

            if (!resp.has("id")) continue;
            if (id.equals(resp.get("id").getAsString())) {
                if (resp.has("error")) {
                    throw new IOException("ACP JSON-RPC error: " + resp.get("error"));
                }
                return resp;
            }
        }
    }

    protected JsonObject buildRequest(String method, JsonObject params) {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", JSONRPC_VERSION);
        request.addProperty("id", idCounter.getAndIncrement());
        request.addProperty("method", method);
        request.add("params", params);
        return request;
    }

    protected void sendJson(JsonObject json) throws IOException {
        String text = gson.toJson(json);
        logger.debug("acp输入 {}", text);
        writer.write(text);
        writer.newLine();
        writer.flush();
    }

    /**
     * 同步发送 prompt 并阻塞等待完整响应文本。
     * <p>
     * 提取自 MemoryAcpClient / AbilityReflectionAcpClient 的公共逻辑，
     * 供所有"同步阻塞式子 Client"复用。自动处理 permission 请求。
     *
     * @param promptText 发送给 agent 的完整 prompt
     * @param clientName 客户端名称，用于异常消息（如 "Memory ACP"、"SubAgent"）
     * @return agent 的完整回答文本
     * @throws IOException 通信失败
     */
    protected String doSendPromptSync(String promptText, String clientName) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);

        JsonArray prompt = new JsonArray();
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", promptText);
        prompt.add(textBlock);
        params.add("prompt", prompt);

        JsonObject request = buildRequest("session/prompt", params);
        String requestId = request.get("id").getAsString();
        sendJson(request);

        StringBuilder fullResponse = new StringBuilder();
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException(clientName + " 进程意外关闭");
            }

            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) continue;

            JsonObject msg;
            try {
                msg = JsonParser.parseString(trimmed).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                continue;
            }

            // prompt response — turn 结束（JSON-RPC Response 没有 method 字段，排除 Request 误匹配）
            if (!msg.has("method") && msg.has("id") && requestId.equals(msg.get("id").getAsString())) {
                drainLateChunksSync(fullResponse);
                return fullResponse.toString();
            }

            // session/request_permission — 自动 allow
            if (msg.has("method") && "session/request_permission".equals(msg.get("method").getAsString())) {
                autoAllowPermission(msg);
                continue;
            }

            // session/update — 拼接文本
            if (msg.has("method") && "session/update".equals(msg.get("method").getAsString())) {
                String text = extractAgentMessageText(msg);
                if (text != null) {
                    fullResponse.append(text);
                }
            }
        }
    }

    /**
     * 自动回复 permission 请求。
     * 从 Agent 提供的 options 中优先选择 allow_always，其次 allow_once。
     */
    protected void autoAllowPermission(JsonObject msg) throws IOException {
        if (!msg.has("id")) return;
        JsonElement permId = msg.get("id");

        logger.info("收到 permission 请求: {}", gson.toJson(msg));

        // 从 options 中找到合适的 optionId
        String selectedOptionId = null;
        JsonObject params = msg.getAsJsonObject("params");
        if (params != null && params.has("options") && params.get("options").isJsonArray()) {
            JsonArray options = params.getAsJsonArray("options");
            String allowOnceId = null;
            for (JsonElement opt : options) {
                if (!opt.isJsonObject()) continue;
                JsonObject optObj = opt.getAsJsonObject();
                String kind = optObj.has("kind") ? optObj.get("kind").getAsString() : "";
                String optId = optObj.has("optionId") ? optObj.get("optionId").getAsString() : "";
                if ("allow_always".equals(kind)) {
                    selectedOptionId = optId;
                    break;
                }
                if ("allow_once".equals(kind) && allowOnceId == null) {
                    allowOnceId = optId;
                }
            }
            if (selectedOptionId == null) {
                selectedOptionId = allowOnceId;
            }
        }
        // fallback：兼容未提供 options 的场景
        if (selectedOptionId == null) {
            selectedOptionId = "allow_always";
        }

        JsonObject outcomeObj = new JsonObject();
        outcomeObj.addProperty("outcome", "selected");
        outcomeObj.addProperty("optionId", selectedOptionId);
        JsonObject permResult = new JsonObject();
        permResult.add("outcome", outcomeObj);
        JsonObject permResp = new JsonObject();
        permResp.addProperty("jsonrpc", JSONRPC_VERSION);
        permResp.add("id", permId);
        permResp.add("result", permResult);
        logger.info("回复 permission: selectedOptionId={}, response={}", selectedOptionId, gson.toJson(permResp));
        sendJson(permResp);
    }

    /**
     * 从 session/update 消息中提取 agent_message_chunk 的文本。
     *
     * @return 文本内容，非 agent_message_chunk 时返回 null
     */
    protected String extractAgentMessageText(JsonObject msg) {
        JsonObject updateParams = msg.getAsJsonObject("params");
        if (updateParams == null) return null;
        JsonObject update = updateParams.getAsJsonObject("update");
        if (update == null) return null;

        String updateType = update.has("sessionUpdate")
                ? update.get("sessionUpdate").getAsString() : "";
        if (!"agent_message_chunk".equals(updateType)) return null;

        JsonObject content = update.getAsJsonObject("content");
        if (content != null && content.has("text")) {
            return content.get("text").getAsString();
        }
        return null;
    }

    /**
     * 排空迟到 chunk（同步版，供 doSendPromptSync 使用）。
     * sleep 让管道里迟到的数据到位，然后一次性抽干 reader 缓冲区。
     */
    private void drainLateChunksSync(StringBuilder fullResponse) throws IOException {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        while (reader.ready()) {
            String line = reader.readLine();
            if (line == null) break;

            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) continue;

            JsonObject msg;
            try {
                msg = JsonParser.parseString(trimmed).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                continue;
            }

            String text = extractAgentMessageText(msg);
            if (text != null) {
                fullResponse.append(text);
            }
        }
    }


    // ==================== Getters ====================

    public String getSessionId() { return sessionId; }
    public String getGroupId() { return groupId; }
    public State getState() { return state.get(); }
    public String getWorkspacePath() { return workspacePath; }
    public double getContextUsagePercentage() { return contextUsagePercentage; }
    protected void setSessionId(String sessionId) { this.sessionId = sessionId; }

    // ==================== Windows PATH 工具方法 ====================

    /**
     * 从 Windows 注册表读取系统级 + 用户级 PATH，合并返回。
     * 解决从精简环境（服务/计划任务）启动时 PATH 不完整的问题。
     */
    private static String readWindowsRegistryPath() {
        try {
            String systemPath = queryRegistry(
                    "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment", "Path");
            String userPath = queryRegistry("HKCU\\Environment", "Path");
            StringBuilder sb = new StringBuilder();
            if (systemPath != null) sb.append(systemPath);
            if (userPath != null) {
                if (sb.length() > 0) sb.append(File.pathSeparator);
                sb.append(userPath);
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            logger.warn("读取 Windows 注册表 PATH 失败: {}", e.getMessage());
            return null;
        }
    }

    private static String queryRegistry(String key, String valueName) throws IOException {
        Process p = new ProcessBuilder("reg", "query", key, "/v", valueName)
                .redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                // 格式: "    Path    REG_SZ    C:\xxx;C:\yyy"  或 REG_EXPAND_SZ
                if (line.contains("REG_SZ") || line.contains("REG_EXPAND_SZ")) {
                    String[] parts = line.split("(REG_SZ|REG_EXPAND_SZ)", 2);
                    if (parts.length == 2) {
                        return parts[1].trim();
                    }
                }
            }
        }
        return null;
    }
}
