package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProviderRouter;
import com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage;
import com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import com.mola.cmd.proxy.app.acp.acpclient.listener.DefaultAcpResponseListener;
import com.mola.cmd.proxy.app.acp.subagent.SubAgentContextInjector;
import com.mola.cmd.proxy.app.acp.subagent.SubAgentDispatcher;
import com.mola.cmd.proxy.app.acp.subagent.DispatchBufferFilter;
import com.mola.cmd.proxy.app.acp.subagent.model.SubAgentResult;
import com.mola.cmd.proxy.app.acp.subagent.model.SubAgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    private final List<Path> mcpConfigPaths;

    /** 会话上下文管理器 */
    private final ConversationHistoryManager historyManager = new ConversationHistoryManager();

    private AcpResponseListener globalListener;

    /** 记忆管理器，通过 setter 注入，未启用时为 null */
    private MemoryManagerBridge memoryManager;

    /** 子 Agent 派发器，通过 setter 注入，未配置子 Agent 时为 null */
    private SubAgentDispatcher subAgentDispatcher;

    /** 子 Agent 上下文注入器，通过 setter 注入 */
    private SubAgentContextInjector subAgentContextInjector;

    /** 全局 robot 注册表引用，用于子 Agent 上下文构建 */
    private Map<String, AcpRobotParam> globalRobotRegistry;

    /** 绑定的 robot 参数，构造时传入，不可变 */
    private final AcpRobotParam robotParam;

    /**
     * 使用指定 AgentProvider 创建 AcpClient（包级私有，供未来扩展）。
     */
    AcpClient(AgentProvider agentProvider, String workspacePath, String groupId, AcpRobotParam robotParam) {
        super(agentProvider, workspacePath, groupId);
        this.robotParam = robotParam;
        this.globalListener = new DefaultAcpResponseListener(groupId);
        this.mcpConfigPaths = agentProvider.getMcpConfigPaths(this.workspacePath);
    }

    /**
     * 使用默认 AgentProvider 创建 AcpClient。
     * 如果 robotParam 指定了 agentProvider，通过路由器解析。
     */
    public AcpClient(String workspacePath, String groupId, AcpRobotParam robotParam) {
        this(AgentProviderRouter.getInstance().resolve(
                robotParam != null ? robotParam.getAgentProvider() : null),
                workspacePath, groupId, robotParam);
    }

    /**
     * 注入记忆管理器。通过桥接接口解耦，避免 acpclient 包直接依赖 memory 包。
     */
    public void setMemoryManager(MemoryManagerBridge memoryManager) {
        this.memoryManager = memoryManager;
    }

    /**
     * 注入子 Agent 派发器和上下文注入器。
     *
     * @param dispatcher      子 Agent 派发器
     * @param injector        上下文注入器
     * @param robotRegistry   全局 robot 注册表
     */
    public void setSubAgentSupport(SubAgentDispatcher dispatcher,
                                   SubAgentContextInjector injector,
                                   Map<String, AcpRobotParam> robotRegistry) {
        this.subAgentDispatcher = dispatcher;
        this.subAgentContextInjector = injector;
        this.globalRobotRegistry = robotRegistry;
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

        // 先取消所有子 Agent
        if (subAgentDispatcher != null) {
            subAgentDispatcher.cancelAll();
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

    @Override
    public void close() throws IOException {
        // 先落盘未保存的上下文，确保数据持久化
        historyManager.forceFlush(sessionId);

        // 提交全量记忆提取到异步队列（队列会在 shutdown 时执行完）
        if (memoryManager != null && sessionId != null) {
            try {
                memoryManager.submitExtractFull(workspacePath, historyManager.getFullHistory(sessionId));
                memoryManager.incrementSessionCount(workspacePath);
            } catch (Exception e) {
                logger.warn("关闭时提交记忆提取失败", e);
            }
        }

        // 关闭子 Agent 派发器
        if (subAgentDispatcher != null) {
            subAgentDispatcher.close();
        }

        executor.shutdownNow();
        super.close();
    }

    // ==================== MCP 配置加载 ====================

    private JsonArray loadMcpServersFromConfigs() {
        return McpConfigLoader.loadFromPaths(mcpConfigPaths);
    }

    // ==================== Prompt ====================

    
    private void sendPrompt(String userInput, Collection<String> imageBase64List, AcpResponseListener listener) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);

        // 注入当前时间上下文
        String timeContext = String.format("[Current Time: %s]\n[Workspace: %s]\n",
                ZonedDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z (EEEE)")),
                workspacePath);

        // 注入记忆上下文
        String memoryContext = "";
        if (memoryManager != null) {
            try {
                memoryContext = memoryManager.buildMemoryPrompt(workspacePath);
            } catch (Exception e) {
                logger.warn("构建记忆上下文失败，跳过", e);
            }
        }

        // 注入子 Agent 上下文
        String subAgentContext = "";
        if (subAgentContextInjector != null && robotParam != null
                && robotParam.hasSubAgents() && globalRobotRegistry != null) {
            try {
                subAgentContext = subAgentContextInjector.buildContext(
                        robotParam.getSubAgents(), globalRobotRegistry);
            } catch (Exception e) {
                logger.warn("构建子 Agent 上下文失败，跳过", e);
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
        // 拼接顺序：子Agent上下文 → 记忆 → 时间 → 用户输入
        StringBuilder fullTextBuilder = new StringBuilder();
        if (!subAgentContext.isEmpty()) fullTextBuilder.append(subAgentContext).append("\n");
        if (!memoryContext.isEmpty()) fullTextBuilder.append(memoryContext).append("\n");
        fullTextBuilder.append(timeContext).append(userInput);
        textBlock.addProperty("text", fullTextBuilder.toString());
        prompt.add(textBlock);
        params.add("prompt", prompt);

        JsonObject request = buildRequest("session/prompt", params);
        String requestId = request.get("id").getAsString();
        sendJson(request);

        historyManager.addUserMessage(userInput);

        // 流式读取
        StringBuilder fullResponse = new StringBuilder();
        // 缓冲过滤器：拦截 dispatch_subagent JSON，避免推送给用户
        DispatchBufferFilter bufferFilter = new DispatchBufferFilter(
                listener, subAgentDispatcher != null);
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

                // flush 缓冲区（如果有未推送的非 dispatch 内容）
                bufferFilter.flush();

                // 检测子 Agent 派发指令并处理
                if (handleSubAgentDispatch(fullResponse.toString(), listener)) {
                    return;
                }

                listener.onComplete(fullResponse.toString());
                return;
            }

            // session/request_permission — 自动回复 allow_always
            if (msg.has("method") && "session/request_permission".equals(msg.get("method").getAsString())) {
                autoAllowPermission(msg);
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
                        // 通过缓冲过滤器推送，dispatch JSON 会被拦截
                        bufferFilter.accept(text);
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

    /**
     * 检测并处理子 Agent 派发指令。
     * <p>
     * 在主 Agent turn 结束后调用。如果检测到 dispatch_subagent 指令：
     * 1. 并行执行子 Agent 任务
     * 2. 将结果格式化为 follow-up prompt
     * 3. 自动发送第二轮 prompt 让主 Agent 汇总
     *
     * @return true 如果检测到并处理了派发指令
     */
    private boolean handleSubAgentDispatch(String fullResponse, AcpResponseListener listener) {
        if (subAgentDispatcher == null) return false;

        List<SubAgentTask> tasks = subAgentDispatcher.detectDispatch(fullResponse);
        if (tasks == null || tasks.isEmpty()) return false;

        logger.info("检测到子 Agent 派发指令，任务数={}", tasks.size());

        try {
            List<SubAgentResult> results = subAgentDispatcher.dispatch(
                    tasks, listener, workspacePath);

            String resultContext = SubAgentDispatcher.formatResults(results);

            listener.onSubAgentEvent("DISPATCH_COMPLETE", null,
                    "正在汇总子 Agent 结果...");
            sendPrompt(resultContext, null, listener);
            return true;

        } catch (Exception e) {
            logger.error("子 Agent 派发处理失败", e);
            listener.onSubAgentEvent("DISPATCH_COMPLETE", null,
                    "子 Agent 派发失败: " + e.getMessage());
            listener.onComplete(fullResponse);
            return true;
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

    public AcpRobotParam getRobotParam() {
        return robotParam;
    }

    public List<Path> getMcpConfigPaths() {
        return Collections.unmodifiableList(mcpConfigPaths);
    }
}
