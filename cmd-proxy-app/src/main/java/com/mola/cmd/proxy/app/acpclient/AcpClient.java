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

/**
 * ACP 主 Client，继承 {@link AbstractAcpClient}，负责：
 * <ul>
 *   <li>MCP Server 配置加载</li>
 *   <li>流式 prompt 读取与回调</li>
 *   <li>图片处理与会话历史管理</li>
 *   <li>记忆系统集成（通过 MemoryManager 注入）</li>
 * </ul>
 */
public class AcpClient extends AbstractAcpClient {

    private static final Logger logger = LoggerFactory.getLogger(AcpClient.class);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "acp-send-worker");
        t.setDaemon(true);
        return t;
    });

    /** MCP 配置文件路径列表，按优先级从低到高排列 */
    private final List<Path> mcpConfigPaths = new ArrayList<>();

    /** 会话上下文管理器 */
    private final ConversationHistoryManager historyManager = new ConversationHistoryManager();

    private AcpResponseListener globalListener;

    /** 记忆管理器，通过 setter 注入，未启用时为 null */
    private MemoryManagerBridge memoryManager;

    public AcpClient(String command, String[] args, String workspacePath, String groupId) {
        super(command, args, workspacePath, groupId);
        this.globalListener = new DefaultAcpResponseListener(groupId);

        // 默认加载路径：用户级 > 工作目录级
        mcpConfigPaths.add(Paths.get(System.getProperty("user.home"), ".kiro", "settings", "mcp.json"));
        mcpConfigPaths.add(Paths.get(workspacePath, ".kiro", "settings", "mcp.json"));
    }

    public void addMcpConfigPath(Path configPath) {
        mcpConfigPaths.add(configPath);
    }

    public void setMcpConfigPaths(List<Path> paths) {
        mcpConfigPaths.clear();
        mcpConfigPaths.addAll(paths);
    }

    /**
     * 注入记忆管理器。通过桥接接口解耦，避免 acpclient 包直接依赖 memory 包。
     */
    public void setMemoryManager(MemoryManagerBridge memoryManager) {
        this.memoryManager = memoryManager;
    }

    // ==================== 生命周期 ====================

    @Override
    protected void createSession() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", workspacePath);

        JsonArray mcpServers = loadMcpServersFromConfigs();
        params.add("mcpServers", mcpServers);
        logger.info("session/new 携带 {} 个 MCP server", mcpServers.size());

        JsonObject response = sendRequest("session/new", params);
        JsonObject result = response.getAsJsonObject("result");
        setSessionId(result.get("sessionId").getAsString());
        logger.info("ACP session 创建成功: {}", getSessionId());
    }

    public void send(String userInput) {
        send(userInput, null);
    }

    public void send(String userInput, List<String> imageBase64List) {
        if (userInput == null || userInput.trim().isEmpty()) {
            globalListener.onError(new IllegalArgumentException("用户输入不能为空"));
            return;
        }
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

    public void cancel() throws IOException {
        if (sessionId == null) {
            logger.warn("cancel 调用时 sessionId 为空，忽略");
            return;
        }
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);

        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", JSONRPC_VERSION);
        notification.addProperty("method", "session/cancel");
        notification.add("params", params);
        sendJson(notification);
        logger.info("已发送 session/cancel, sessionId={}", sessionId);
    }

    public void closeSession(String targetSessionId) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", targetSessionId);
        sendRequest("session/close", params);
        logger.info("session/close 成功, sessionId={}", targetSessionId);
    }

    @Override
    public void close() throws IOException {
        // 先落盘未保存的上下文，确保数据持久化
        historyManager.forceFlush(sessionId);

        // 提交全量记忆提取到异步队列（队列会在 shutdown 时执行完）
        if (memoryManager != null && sessionId != null) {
            try {
                memoryManager.submitExtractFull(workspacePath, historyManager.getFullHistory(sessionId));
            } catch (Exception e) {
                logger.warn("关闭时提交记忆提取失败", e);
            }
        }

        executor.shutdownNow();
        super.close();
    }

    // ==================== MCP 配置加载 ====================

    private JsonArray loadMcpServersFromConfigs() {
        Map<String, JsonObject> serverMap = new LinkedHashMap<>();
        for (Path configPath : mcpConfigPaths) {
            loadMcpServersFromConfig(configPath, serverMap);
        }
        JsonArray result = new JsonArray();
        serverMap.values().forEach(result::add);
        return result;
    }

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
                if (serverObj.has("disabled") && serverObj.get("disabled").getAsBoolean()) {
                    continue;
                }
                if (serverMap.containsKey(name)) {
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

    private JsonObject convertToAcpFormat(String name, JsonObject serverObj) {
        JsonObject acpServer = new JsonObject();
        acpServer.addProperty("name", name);

        if (serverObj.has("url")) {
            acpServer.addProperty("type", "http");
            acpServer.addProperty("url", serverObj.get("url").getAsString());
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
            acpServer.addProperty("command", serverObj.get("command").getAsString());
            if (serverObj.has("args") && serverObj.get("args").isJsonArray()) {
                acpServer.add("args", serverObj.getAsJsonArray("args"));
            } else {
                acpServer.add("args", new JsonArray());
            }
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

    // ==================== Prompt ====================

    private void sendPrompt(String userInput, Collection<String> imageBase64List, AcpResponseListener listener) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);

        // 注入当前时间上下文
        String timeContext = String.format("[Current Time: %s]\n",
                ZonedDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z (EEEE)")));

        // 注入记忆上下文
        String memoryContext = "";
        if (memoryManager != null) {
            try {
                memoryContext = memoryManager.buildMemoryPrompt(workspacePath);
            } catch (Exception e) {
                logger.warn("构建记忆上下文失败，跳过", e);
            }
        }

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
        String fullText = memoryContext.isEmpty()
                ? timeContext + userInput
                : memoryContext + "\n" + timeContext + userInput;
        textBlock.addProperty("text", fullText);
        prompt.add(textBlock);
        params.add("prompt", prompt);

        JsonObject request = buildRequest("session/prompt", params);
        String requestId = request.get("id").getAsString();
        sendJson(request);

        historyManager.addUserMessage(userInput);

        // 流式读取
        StringBuilder fullResponse = new StringBuilder();
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                listener.onError(new IOException("ACP 进程意外关闭"));
                return;
            }

            String trimmed = line.trim();
            logger.debug("acp输出 {}", trimmed);
            if (!trimmed.startsWith("{")) continue;

            JsonObject msg;
            try {
                msg = JsonParser.parseString(trimmed).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                logger.warn("跳过非法 JSON: {}", line);
                continue;
            }

            // prompt response
            if (msg.has("id") && requestId.equals(msg.get("id").getAsString())) {
                String stopReason = "unknown";
                if (msg.has("result") && msg.getAsJsonObject("result").has("stopReason")) {
                    stopReason = msg.getAsJsonObject("result").get("stopReason").getAsString();
                }
                logger.info("ACP prompt turn 结束, stopReason={}, msg = {}", stopReason, trimmed);
                historyManager.addAssistantMessage(fullResponse.toString());
                historyManager.flushTurn(sessionId);
                listener.onComplete(fullResponse.toString());
                return;
            }

            // session/request_permission — 自动回复 allow_always
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

            // session/update
            if (msg.has("method") && "session/update".equals(msg.get("method").getAsString())) {
                JsonObject updateParams = msg.getAsJsonObject("params");
                if (updateParams == null) continue;
                JsonObject update = updateParams.getAsJsonObject("update");
                if (update == null) continue;

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

    private String guessMimeType(String base64Data) {
        if (base64Data.startsWith("iVBOR")) return "image/png";
        if (base64Data.startsWith("/9j/")) return "image/jpeg";
        if (base64Data.startsWith("R0lG")) return "image/gif";
        if (base64Data.startsWith("UklGR")) return "image/webp";
        return "image/png";
    }

    // ==================== Getters ====================

    public void setGlobalListener(AcpResponseListener listener) {
        if (listener != null) {
            this.globalListener = listener;
        }
    }

    public AcpResponseListener getGlobalListener() {
        return globalListener;
    }

    public ConversationHistoryManager getHistoryManager() {
        return historyManager;
    }

    public List<ContextMessage> getConversationHistory() {
        return historyManager.getFullHistory(sessionId);
    }
}
