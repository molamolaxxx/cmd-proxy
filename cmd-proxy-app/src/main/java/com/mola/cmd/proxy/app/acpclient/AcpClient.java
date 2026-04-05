package com.mola.cmd.proxy.app.acpclient;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ACP (Agent Client Protocol) Client，通过子进程执行 kiro-cli acp 与 agent 通信。
 * <p>
 * ACP 基于 JSON-RPC 2.0 over stdio，协议流程:
 * <ol>
 *   <li>initialize — 协议版本协商 + 能力声明</li>
 *   <li>session/new — 创建会话，获取 sessionId</li>
 *   <li>session/prompt — 发送用户消息，通过 session/update 通知流式接收 agent 回答</li>
 * </ol>
 */
public class AcpClient implements Closeable {

    /**
     * AcpClient 生命周期状态
     */
    public enum State {
        /** 已构造，尚未启动 */
        CREATED,
        /** 正在启动（startProcess + initialize + createSession） */
        STARTING,
        /** 就绪，可收发消息 */
        READY,
        /** 正在处理 prompt */
        BUSY,
        /** 发生错误 */
        ERROR,
        /** 已关闭 */
        CLOSED
    }

    private static final Logger logger = LoggerFactory.getLogger(AcpClient.class);
    private static final String JSONRPC_VERSION = "2.0";
    private static final int PROTOCOL_VERSION = 1;
    private static final String CLIENT_NAME = "cmd-proxy-acp";
    private static final String CLIENT_VERSION = "1.0.0";

    private static final String DEFAULT_COMMAND = System.getProperty("user.home") + "/.local/bin/kiro-cli";
    private static final String[] DEFAULT_ARGS = {"acp"};

    private final String command;
    private final String[] args;
    private final String workspacePath;
    private final String groupId;
    private final Gson gson = new GsonBuilder().create();
    private final AtomicInteger idCounter = new AtomicInteger(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.CREATED);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "acp-send-worker");
        t.setDaemon(true);
        return t;
    });

    /** MCP 配置文件路径列表，按优先级从低到高排列，后加载的同名 server 会跳过 */
    private final List<Path> mcpConfigPaths = new ArrayList<>();

    /** 会话上下文管理器 */
    private final ConversationHistoryManager historyManager = new ConversationHistoryManager();

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private String sessionId;
    private AcpResponseListener globalListener;

    public AcpClient(String command, String[] args, String workspacePath, String groupId) {
        this.command = command;
        this.args = args;
        this.workspacePath = workspacePath;
        this.groupId = groupId;
        this.globalListener = new DefaultAcpResponseListener(groupId);

        // 默认加载路径：用户级 > 工作目录级
        mcpConfigPaths.add(Paths.get(System.getProperty("user.home"), ".kiro", "settings", "mcp.json"));
        mcpConfigPaths.add(Paths.get(workspacePath, ".kiro", "settings", "mcp.json"));
    }

    /**
     * 添加额外的 mcp.json 配置路径（后添加的优先级更高）
     */
    public void addMcpConfigPath(Path configPath) {
        mcpConfigPaths.add(configPath);
    }

    /**
     * 清空并重新设置 mcp.json 配置路径列表
     */
    public void setMcpConfigPaths(List<Path> paths) {
        mcpConfigPaths.clear();
        mcpConfigPaths.addAll(paths);
    }

    // ==================== 生命周期 ====================

    /**
     * 启动 ACP Client：启动子进程 → initialize → session/new
     */
    public void start() throws IOException {
        state.set(State.STARTING);
        try {
            startProcess();
            initialize();
            createSession();
            state.set(State.READY);
            logger.info("ACP Client 就绪，sessionId={}", sessionId);
        } catch (IOException e) {
            state.set(State.ERROR);
            throw e;
        }
    }


    public void send(String userInput) {
        send(userInput, null);
    }

    /**
     * 发送用户消息，可附带图片（base64 编码）。
     * 新图片会追加到 historyManager，每次 prompt 都会带上历史所有图片。
     *
     * @param userInput       用户文本消息
     * @param imageBase64List 本次新增的图片 base64 列表，可为 null
     */
    public void send(String userInput, List<String> imageBase64List) {
        if (userInput == null || userInput.trim().isEmpty()) {
            globalListener.onError(new IllegalArgumentException("用户输入不能为空"));
            return;
        }
        // 追加新图片到历史（去重）
        historyManager.addImages(imageBase64List);
        state.set(State.BUSY);
        executor.submit(() -> {
            try {
                sendPrompt(userInput, historyManager.getImageBase64History(), globalListener);
                state.set(State.READY);
            } catch (Exception e) {
                logger.error("ACP send 失败", e);
                state.set(State.ERROR);
                globalListener.onError(e);
            }
        });
    }

    /**
     * 取消当前正在进行的 prompt turn。
     * <p>
     * 发送 session/cancel notification，Agent 会停止当前 LLM 请求和 tool call，
     * 然后对原始 session/prompt 返回 stopReason="cancelled"。
     * <p>
     * 注意：cancel 不影响 session 上下文和历史，后续可继续发送 session/prompt。
     *
     * @throws IOException 发送失败时抛出
     */
    public void cancel() throws IOException {
        if (sessionId == null) {
            logger.warn("cancel 调用时 sessionId 为空，忽略");
            return;
        }
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);

        // session/cancel 是 notification，没有 id 字段
        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", JSONRPC_VERSION);
        notification.addProperty("method", "session/cancel");
        notification.add("params", params);
        sendJson(notification);
        logger.info("已发送 session/cancel, sessionId={}", sessionId);
    }


    /**
     * 关闭指定 session，释放 Agent 端资源。
     * <p>
     * 基于 ACP Draft RFD "Closing active sessions"，调用 session/close 方法。
     * 注意：该方法目前处于 Draft 阶段，并非所有 Agent 都支持。
     *
     * @param targetSessionId 要关闭的 session ID
     * @throws IOException 如果 Agent 不支持或调用失败
     * @see <a href="https://agentclientprotocol.com/rfds/session-stop">session/close RFD</a>
     */
    public void closeSession(String targetSessionId) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", targetSessionId);
        sendRequest("session/close", params);
        logger.info("session/close 成功, sessionId={}", targetSessionId);
    }

    // ==================== 协议步骤 ====================

    private void startProcess() throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        cmd.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);

        String home = System.getProperty("user.home");
        String currentPath = pb.environment().getOrDefault("PATH", "");
        String extraPaths = home + "/.local/bin"
                + File.pathSeparator + home + "/.cargo/bin"
                + File.pathSeparator + "/usr/local/bin";
        if (!currentPath.contains(home + "/.local/bin")) {
            pb.environment().put("PATH", extraPaths + File.pathSeparator + currentPath);
        }

        logger.info("启动 ACP 进程: {}", cmd);
        process = pb.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        // stderr 日志转发
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = errReader.readLine()) != null) {
                    logger.debug("[ACP STDERR] {}", line);
                }
            } catch (IOException e) {
                // 进程关闭时正常退出
            }
        }, "acp-stderr-reader");
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    /**
     * initialize — 协议版本协商 + 能力声明
     */
    private void initialize() throws IOException {
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

    /**
     * session/new — 创建会话，自动从 mcp.json 配置文件加载 MCP servers 传给 Agent
     */
    private void createSession() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", workspacePath);

        // 从配置文件加载 MCP servers，转换为 ACP 协议格式传给 Agent
        JsonArray mcpServers = loadMcpServersFromConfigs();
        params.add("mcpServers", mcpServers);
        logger.info("session/new 携带 {} 个 MCP server", mcpServers.size());

        JsonObject response = sendRequest("session/new", params);
        JsonObject result = response.getAsJsonObject("result");
        sessionId = result.get("sessionId").getAsString();
        logger.info("ACP session 创建成功: {}", sessionId);
    }

    // ==================== MCP 配置加载 ====================

    /**
     * 从所有配置路径加载 MCP servers，合并为 ACP 协议所需的 mcpServers 数组。
     * <p>
     * 按 mcpConfigPaths 顺序依次加载，同名 server 以先加载的为准（不覆盖）。
     */
    private JsonArray loadMcpServersFromConfigs() {
        // name -> ACP server JsonObject，用于去重
        Map<String, JsonObject> serverMap = new LinkedHashMap<>();

        for (Path configPath : mcpConfigPaths) {
            loadMcpServersFromConfig(configPath, serverMap);
        }

        JsonArray result = new JsonArray();
        serverMap.values().forEach(result::add);
        return result;
    }

    /**
     * 从单个 mcp.json 文件解析 MCP server 配置，转换为 ACP session/new 所需格式。
     * <p>
     * mcp.json 格式（Kiro 标准）:
     * <pre>
     * {
     *   "mcpServers": {
     *     "server-name": {
     *       "command": "uvx",
     *       "args": ["package@latest"],
     *       "env": { "KEY": "VALUE" },
     *       "disabled": false
     *     }
     *   }
     * }
     * </pre>
     * <p>
     * 转换为 ACP 协议格式:
     * <pre>
     * { "type": "stdio", "name": "server-name", "command": "uvx", "args": ["package@latest"], "env": { "KEY": "VALUE" } }
     * </pre>
     *
     * @param configPath mcp.json 文件路径
     * @param serverMap  已加载的 server 集合，同名 server 不会被覆盖
     */
    private void loadMcpServersFromConfig(Path configPath, Map<String, JsonObject> serverMap) {
        if (!Files.exists(configPath)) {
            logger.debug("MCP config not found, skipping: {}", configPath);
            return;
        }

        try {
            String content = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            JsonObject servers = root.getAsJsonObject("mcpServers");
            if (servers == null) {
                logger.debug("No mcpServers in config: {}", configPath);
                return;
            }

            int loaded = 0;
            for (Map.Entry<String, JsonElement> entry : servers.entrySet()) {
                String name = entry.getKey();
                JsonObject serverObj = entry.getValue().getAsJsonObject();

                // 跳过 disabled
                if (serverObj.has("disabled") && serverObj.get("disabled").getAsBoolean()) {
                    logger.debug("Skipping disabled MCP server: {} from {}", name, configPath);
                    continue;
                }

                // 同名 server 不覆盖
                if (serverMap.containsKey(name)) {
                    logger.debug("MCP server '{}' already loaded, skipping from {}", name, configPath);
                    continue;
                }

                JsonObject acpServer = convertToAcpFormat(name, serverObj);
                if (acpServer != null) {
                    serverMap.put(name, acpServer);
                    loaded++;
                }
            }

            logger.info("Loaded {} MCP server(s) from {}", loaded, configPath);
        } catch (Exception e) {
            logger.error("Failed to load MCP config from {}", configPath, e);
        }
    }

    /**
     * 将 mcp.json 中的单个 server 配置转换为 ACP 协议的 McpServer 格式。
     * <p>
     * ACP 协议使用 untagged enum 区分 transport 类型：
     * <ul>
     *   <li>Stdio: 无 type 字段，包含 name/command/args/env</li>
     *   <li>HTTP:  type="http"，包含 name/url/headers</li>
     *   <li>SSE:   type="sse"，包含 name/url/headers</li>
     * </ul>
     * env 和 headers 在 ACP 中均为数组格式 [{name, value}]，而非 mcp.json 中的对象格式。
     *
     * @see <a href="https://agentclientprotocol.com/protocol/session-setup">ACP Session Setup</a>
     */
    private JsonObject convertToAcpFormat(String name, JsonObject serverObj) {
        JsonObject acpServer = new JsonObject();
        acpServer.addProperty("name", name);

        if (serverObj.has("url")) {
            // HTTP 模式：需要 type 字段
            acpServer.addProperty("type", "http");
            acpServer.addProperty("url", serverObj.get("url").getAsString());

            // headers: mcp.json 中是 {"K":"V"} 对象，ACP 中是 [{"name":"K","value":"V"}] 数组
            if (serverObj.has("headers") && serverObj.get("headers").isJsonObject()) {
                JsonArray headerArray = new JsonArray();
                for (Map.Entry<String, JsonElement> h : serverObj.getAsJsonObject("headers").entrySet()) {
                    JsonObject header = new JsonObject();
                    header.addProperty("name", h.getKey());
                    header.addProperty("value", h.getValue().getAsString());
                    headerArray.add(header);
                }
                acpServer.add("headers", headerArray);
            }
        } else if (serverObj.has("command")) {
            // Stdio 模式：不需要 type 字段（ACP untagged enum 靠字段组合推断）
            acpServer.addProperty("command", serverObj.get("command").getAsString());

            if (serverObj.has("args") && serverObj.get("args").isJsonArray()) {
                acpServer.add("args", serverObj.getAsJsonArray("args"));
            } else {
                acpServer.add("args", new JsonArray());
            }

            // env: mcp.json 中是 {"K":"V"} 对象，ACP 中是 [{"name":"K","value":"V"}] 数组
            if (serverObj.has("env") && serverObj.get("env").isJsonObject()) {
                JsonArray envArray = new JsonArray();
                for (Map.Entry<String, JsonElement> e : serverObj.getAsJsonObject("env").entrySet()) {
                    JsonObject envVar = new JsonObject();
                    envVar.addProperty("name", e.getKey());
                    envVar.addProperty("value", e.getValue().getAsString());
                    envArray.add(envVar);
                }
                acpServer.add("env", envArray);
            } else {
                acpServer.add("env", new JsonArray());
            }
        } else {
            logger.warn("MCP server '{}' has neither 'url' nor 'command', skipping", name);
            return null;
        }

        return acpServer;
    }

    /**
     * session/prompt — 发送用户消息，流式读取 session/update 通知
     */
    private void sendPrompt(String userInput, Collection<String> imageBase64List, AcpResponseListener listener) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);

        // 注入当前时间上下文，解决 kiro-cli 无法获取准确时间的问题
        String timeContext = String.format("[Current Time: %s]\n",
                ZonedDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z (EEEE)")));

        JsonArray prompt = new JsonArray();

        // 添加图片内容块
        if (imageBase64List != null) {
            for (String base64Data : imageBase64List) {
                if (base64Data == null || base64Data.isEmpty()) continue;
                JsonObject imageBlock = new JsonObject();
                imageBlock.addProperty("type", "image");
                imageBlock.addProperty("mimeType", guessMimeType(base64Data));
                imageBlock.addProperty("data", base64Data);
                prompt.add(imageBlock);
            }
        }

        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", timeContext + userInput);
        prompt.add(textBlock);
        params.add("prompt", prompt);

        JsonObject request = buildRequest("session/prompt", params);
        String requestId = request.get("id").getAsString();
        sendJson(request);

        // 记录用户消息到会话历史
        historyManager.addUserMessage(userInput);

        // 流式读取：notification 为 session/update，response 为 prompt 结束
        StringBuilder fullResponse = new StringBuilder();
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                listener.onError(new IOException("ACP 进程意外关闭"));
                return;
            }

            String trimmed = line.trim();
            logger.debug("acp输出 {}", trimmed);
            if (!trimmed.startsWith("{")) {
                continue;
            }

            JsonObject msg;
            try {
                msg = JsonParser.parseString(trimmed).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                logger.warn("跳过非法 JSON: {}", line);
                continue;
            }

            // 如果是 response（有 id），检查是否是 prompt 的响应
            if (msg.has("id") && requestId.equals(msg.get("id").getAsString())) {
                // prompt turn 结束
                String stopReason = "unknown";
                if (msg.has("result") && msg.getAsJsonObject("result").has("stopReason")) {
                    stopReason = msg.getAsJsonObject("result").get("stopReason").getAsString();
                }
                logger.info("ACP prompt turn 结束, stopReason={}, msg = {}", stopReason, trimmed);
                // 记录 agent 回答到会话历史
                historyManager.addAssistantMessage(fullResponse.toString());
                // 落盘本轮上下文并清空内存
                historyManager.flushTurn(sessionId);
                listener.onComplete(fullResponse.toString());
                return;
            }

            // request: session/request_permission — 自动回复 allow_once
            if (msg.has("method") && "session/request_permission".equals(msg.get("method").getAsString())) {
                String permId = msg.has("id") ? msg.get("id").getAsString() : null;
                if (permId != null) {
                    JsonObject outcomeObj = new JsonObject();
                    outcomeObj.addProperty("outcome", "selected");
                    outcomeObj.addProperty("optionId", "allow_always");
                    JsonObject permResult = new JsonObject();
                    permResult.add("outcome", outcomeObj);
                    JsonObject permResp = new JsonObject();
                    permResp.addProperty("jsonrpc", JSONRPC_VERSION);
                    permResp.addProperty("id", permId);
                    permResp.add("result", permResult);
                    sendJson(permResp);
                }
                continue;
            }

            // notification: session/update
            if (msg.has("method") && "session/update".equals(msg.get("method").getAsString())) {
                JsonObject updateParams = msg.getAsJsonObject("params");
                if (updateParams == null) continue;
                JsonObject update = updateParams.getAsJsonObject("update");
                if (update == null) {
                    continue;
                }

                String updateType = update.has("sessionUpdate")
                        ? update.get("sessionUpdate").getAsString() : "";

                if ("agent_message_chunk".equals(updateType)) {
                    JsonObject content = update.getAsJsonObject("content");
                    if (content != null && content.has("text")) {
                        String text = content.get("text").getAsString();
                        fullResponse.append(text);
                        listener.onMessage(text);
                    }
                } else if ("tool_call".equals(updateType) || "tool_call_update".equals(updateType)) {
                    String toolCallId = update.has("toolCallId") ? update.get("toolCallId").getAsString() : "";
                    String title = update.has("title") ? update.get("title").getAsString() : "";
                    String status = update.has("status") ? update.get("status").getAsString() : "pending";
                    // 工具调用完成时，记录到会话历史
                    if ("completed".equals(status)) {
                        JsonObject rawInput = update.has("rawInput") ? update.getAsJsonObject("rawInput") : null;
                        JsonObject rawOutput = update.has("rawOutput") ? update.getAsJsonObject("rawOutput") : null;
                        historyManager.addToolMessage(toolCallId, title, status, rawInput, rawOutput);
                    }
                    listener.onToolCall(toolCallId, title, status, update);
                } else {
                    logger.warn("ACP IN session/update 输出未匹配任何处理分支, msg={}", msg);
                }
            } else {
                logger.warn("ACP 输出未匹配任何处理分支, msg={}", msg);
            }
        }
    }

    /**
     * 根据 base64 数据的前几个字节猜测图片 MIME 类型
     */
    private String guessMimeType(String base64Data) {
        if (base64Data.startsWith("iVBOR")) return "image/png";
        if (base64Data.startsWith("/9j/")) return "image/jpeg";
        if (base64Data.startsWith("R0lG")) return "image/gif";
        if (base64Data.startsWith("UklGR")) return "image/webp";
        return "image/png"; // 默认 png
    }

    // ==================== JSON-RPC 工具方法 ====================

    private JsonObject sendRequest(String method, JsonObject params) throws IOException {
        JsonObject request = buildRequest(method, params);
        String id = request.get("id").getAsString();
        sendJson(request);

        // 读取响应，跳过 notification
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

            if (!resp.has("id")) continue; // 跳过 notification
            if (id.equals(resp.get("id").getAsString())) {
                if (resp.has("error")) {
                    throw new IOException("ACP JSON-RPC error: " + resp.get("error"));
                }
                return resp;
            }
        }
    }

    private JsonObject buildRequest(String method, JsonObject params) {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", JSONRPC_VERSION);
        request.addProperty("id", idCounter.getAndIncrement());
        request.addProperty("method", method);
        request.add("params", params);
        return request;
    }

    private void sendJson(JsonObject json) throws IOException {
        String text = gson.toJson(json);
        logger.debug("acp输入 {}", text);

        writer.write(text);
        writer.newLine();
        writer.flush();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getGroupId() {
        return groupId;
    }

    public State getState() {
        return state.get();
    }

    public void setGlobalListener(AcpResponseListener listener) {
        if (listener != null) {
            this.globalListener = listener;
        }
    }

    public AcpResponseListener getGlobalListener() {
        return globalListener;
    }

    @Override
    public void close() throws IOException {
        state.set(State.CLOSED);
        logger.info("关闭 AcpClient...");

        // 落盘未保存的上下文
        historyManager.forceFlush(sessionId);

        executor.shutdownNow();
        try { if (writer != null) writer.close(); } catch (IOException e) { /* ignore */ }
        try { if (reader != null) reader.close(); } catch (IOException e) { /* ignore */ }
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    /**
     * 获取会话上下文管理器。
     */
    public ConversationHistoryManager getHistoryManager() {
        return historyManager;
    }

    /**
     * 获取当前 session 的完整会话上下文。
     */
    public List<ContextMessage> getConversationHistory() {
        return historyManager.getFullHistory(sessionId);
    }
}
