package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.*;
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
    protected final Gson gson = new GsonBuilder().create();
    protected final AtomicInteger idCounter = new AtomicInteger(0);
    protected final AtomicReference<State> state = new AtomicReference<>(State.CREATED);

    protected Process process;
    protected BufferedWriter writer;
    protected BufferedReader reader;
    protected String sessionId;

    /**
     * 使用指定 AgentProvider 创建（protected，供子类使用）。
     */
    protected AbstractAcpClient(AgentProvider agentProvider, String workspacePath, String groupId) {
        this.agentProvider = agentProvider;
        this.workspacePath = (workspacePath == null || workspacePath.trim().isEmpty())
                ? System.getProperty("user.home")
                : workspacePath;
        this.groupId = groupId;
    }

    /**
     * 使用默认 KiroCliAgentProvider 创建。
     */
    public AbstractAcpClient(String workspacePath, String groupId) {
        this(new KiroCliAgentProvider(), workspacePath, groupId);
    }

    // ==================== 生命周期（模板方法） ====================

    /**
     * 启动 ACP Client：启动子进程 → initialize → createSession
     */
    public void start() throws IOException {
        state.set(State.STARTING);
        try {
            startProcess();
            initialize();
            createSession();
            state.set(State.READY);
            logger.info("ACP Client 就绪，sessionId={}, groupId={}", sessionId, groupId);
        } catch (IOException e) {
            state.set(State.ERROR);
            throw e;
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

            // prompt response — turn 结束
            if (msg.has("id") && requestId.equals(msg.get("id").getAsString())) {
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
     * 自动回复 permission 请求为 allow_always。
     */
    protected void autoAllowPermission(JsonObject msg) throws IOException {
        String permId = msg.has("id") ? msg.get("id").getAsString() : null;
        if (permId == null) return;

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


    // ==================== Getters ====================

    public String getSessionId() { return sessionId; }
    public String getGroupId() { return groupId; }
    public State getState() { return state.get(); }
    public String getWorkspacePath() { return workspacePath; }

    protected void setSessionId(String sessionId) { this.sessionId = sessionId; }
}
