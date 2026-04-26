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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *   <li>文件处理与会话历史管理</li>
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
    private final ConversationHistoryManager historyManager;

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

    /** 强制创建新会话，跳过历史会话恢复（用于 clearContext 场景） */
    private boolean forceNewSession = false;

    /** 指定恢复的目标 sessionId（用于 acpRestoreSession 场景） */
    private String targetRestoreSessionId;

    /** session 加载完成时的 turn 数，用于 close 时判断是否有新对话 */
    private int initialTurnCount;

    /**
     * 使用指定 AgentProvider 创建 AcpClient（包级私有，供未来扩展）。
     */
    AcpClient(AgentProvider agentProvider, String workspacePath, String groupId, AcpRobotParam robotParam) {
        super(agentProvider, workspacePath, groupId);
        this.robotParam = robotParam;
        this.historyManager = new ConversationHistoryManager(
                robotParam != null && !robotParam.getName().isEmpty()
                        ? robotParam.getName() : groupId);
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
                // 指定恢复目标 sessionId（acpRestoreSession 场景）
                if (targetRestoreSessionId != null) {
                    try {
                        loadSession(targetRestoreSessionId);
                    } catch (IOException e) {
                        if (tryKillConflictingProcess(e)) {
                            logger.info("已终止占用进程，重试 session/load (restore), sessionId={}", targetRestoreSessionId);
                            loadSession(targetRestoreSessionId);
                        } else {
                            throw e;
                        }
                    }
                    historyManager.saveLastSessionId(targetRestoreSessionId);
                    return;
                }

                // clearContext 场景下强制创建新会话，跳过历史恢复
                if (!forceNewSession) {
                    String latestSessionId = historyManager.findLatestSessionId();
                    if (latestSessionId != null) {
                        try {
                            loadSession(latestSessionId);
                            return;
                        } catch (IOException e) {
                            // 如果是 "Session is active in another process"，尝试 kill 占用进程后重试
                            if (tryKillConflictingProcess(e)) {
                                try {
                                    logger.info("已终止占用进程，重试 session/load, sessionId={}", latestSessionId);
                                    loadSession(latestSessionId);
                                    return;
                                } catch (IOException retryEx) {
                                    logger.warn("终止占用进程后 session/load 仍然失败，回退到 session/new, sessionId={}", latestSessionId, retryEx);
                                }
                            } else {
                                logger.warn("session/load 失败，回退到 session/new, sessionId={}", latestSessionId, e);
                            }
                            historyManager.reset();
                        }
                    }
                }

                newSession();
            }

        private static final Pattern PID_PATTERN = Pattern.compile("PID\\s+(\\d+)");

        /**
         * 检查异常是否为 "Session is active in another process" 错误，
         * 如果是则尝试 kill 该进程。
         *
         * @return true 表示成功终止了占用进程，调用方可以重试 loadSession
         */
        private boolean tryKillConflictingProcess(IOException e) {
            String message = e.getMessage();
            if (message == null || !message.contains("Session is active in another process")) {
                return false;
            }
            Matcher matcher = PID_PATTERN.matcher(message);
            if (!matcher.find()) {
                logger.warn("检测到会话被其他进程占用，但无法提取 PID: {}", message);
                return false;
            }
            String pid = matcher.group(1);
            logger.info("检测到会话被进程占用, PID={}, 尝试终止该进程", pid);
            try {
                ProcessBuilder pb = new ProcessBuilder("kill", "-9", pid);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    logger.info("成功终止占用进程, PID={}", pid);
                    // 等待一小段时间让资源释放
                    Thread.sleep(500);
                    return true;
                } else {
                    logger.warn("终止进程失败, PID={}, exitCode={}", pid, exitCode);
                    return false;
                }
            } catch (Exception ex) {
                logger.warn("终止占用进程时发生异常, PID={}", pid, ex);
                return false;
            }
        }


        /**
         * 通过 session/load 恢复历史会话。
         * Agent 会通过 session/update 回放完整对话历史，全部回放完成后返回响应。
         */
        private void loadSession(String targetSessionId) throws IOException {
            JsonObject params = new JsonObject();
            params.addProperty("sessionId", targetSessionId);
            params.addProperty("cwd", workspacePath);

            JsonArray mcpServers = loadMcpServersFromConfigs();
            params.add("mcpServers", mcpServers);
            logger.info("session/load 尝试恢复会话: {}, 携带 {} 个 MCP server", targetSessionId, mcpServers.size());

            JsonObject request = buildRequest("session/load", params);
            String requestId = request.get("id").getAsString();
            sendJson(request);

            // 读取 agent 回放的 session/update 通知，直到收到 load 响应
            int replayedMessages = 0;
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    throw new IOException("ACP 进程在 session/load 期间意外关闭");
                }
                String trimmed = line.trim();
                if (!trimmed.startsWith("{")) continue;

                JsonObject msg;
                try {
                    msg = JsonParser.parseString(trimmed).getAsJsonObject();
                } catch (JsonSyntaxException e) {
                    continue;
                }

                // 匹配到 load 响应，回放结束
                if (msg.has("id") && requestId.equals(msg.get("id").getAsString())) {
                    if (msg.has("error")) {
                        throw new IOException("session/load 返回错误: " + msg.get("error"));
                    }
                    setSessionId(targetSessionId);
                    historyManager.restoreState(targetSessionId);
                    initialTurnCount = historyManager.getTurnCount();
                    logger.info("session/load 成功，已恢复会话: {}, 回放消息数={}", targetSessionId, replayedMessages);
                    return;
                }

                // 处理回放的 session/update 通知（静默消费，不推送给 listener）
                if (msg.has("method") && "session/update".equals(msg.get("method").getAsString())) {
                    replayedMessages++;
                    logger.debug("session/load 回放消息: {}", trimmed);
                }
            }
        }

        /**
         * 创建全新的 ACP 会话。
         */
        private void newSession() throws IOException {
            JsonObject params = new JsonObject();
            params.addProperty("cwd", workspacePath);

            JsonArray mcpServers = loadMcpServersFromConfigs();
            params.add("mcpServers", mcpServers);
            logger.info("session/new 携带 {} 个 MCP server", mcpServers.size());

            JsonObject response = sendRequest("session/new", params);
            JsonObject result = response.getAsJsonObject("result");
            setSessionId(result.get("sessionId").getAsString());
            historyManager.saveLastSessionId(getSessionId());
            logger.info("ACP session 创建成功: {}", getSessionId());
        }


    public void send(String userInput, List<Map<String, String>> files) {
        if (userInput == null || userInput.trim().isEmpty()) {
            globalListener.onError(new IllegalArgumentException("用户输入不能为空"));
            return;
        }
        historyManager.saveFiles(sessionId, files);
        state.set(State.BUSY);
        executor.submit(() -> {
            try {
                sendPrompt(userInput, historyManager.getFileAbsolutePaths(), globalListener);
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
                if (historyManager.getTurnCount() > initialTurnCount) {
                    memoryManager.submitExtractFull(workspacePath, historyManager.getFullHistory(sessionId));
                    memoryManager.incrementSessionCount(workspacePath);
                } else {
                    logger.info("本次 session 无新对话，跳过记忆提取, sessionId={}", sessionId);
                }
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

    
    private void sendPrompt(String userInput, Collection<String> filePaths, AcpResponseListener listener) throws IOException {
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
                        robotParam.getSubAgents(), globalRobotRegistry, robotParam.getName());
            } catch (Exception e) {
                logger.warn("构建子 Agent 上下文失败，跳过", e);
            }
        }

        // 构建文件路径上下文
        String fileContext = "";
        if (filePaths != null && !filePaths.isEmpty()) {
            StringBuilder fb = new StringBuilder("[Attached Files]\n");
            for (String path : filePaths) {
                fb.append("- ").append(path).append("\n");
            }
            fileContext = fb.toString();
        }

        JsonArray prompt = new JsonArray();

        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        // 拼接顺序：子Agent上下文 → 记忆 → 文件路径 → 时间 → 用户输入
        StringBuilder fullTextBuilder = new StringBuilder();
        if (!subAgentContext.isEmpty()) fullTextBuilder.append(subAgentContext).append("\n");
        if (!memoryContext.isEmpty()) fullTextBuilder.append(memoryContext).append("\n");
        if (!fileContext.isEmpty()) fullTextBuilder.append(fileContext).append("\n");
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
                        // 访问强化：检测 Agent 是否读取了记忆明细文件
                        detectMemoryAccess(rawInput);
                    }
                    listener.onToolCall(toolCallId, title, status, update);
                } else {
                    logger.warn("ACP IN session/update 输出未匹配任何处理分支, msg={}", msg);
                }
            } else {
                double usage = agentProvider.extractContextUsage(msg);
                if (usage >= 0) {
                    contextUsagePercentage = usage;
                } else {
                    logger.warn("ACP 输出未匹配任何处理分支, msg={}", msg);
                }
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



    /**
     * 检测工具调用是否读取了记忆明细文件，触发访问强化。
     */
    private void detectMemoryAccess(JsonObject rawInput) {
        if (memoryManager == null || rawInput == null) return;
        try {
            // 兼容多种工具输入格式：ops[].path 或直接 path
            if (rawInput.has("ops") && rawInput.get("ops").isJsonArray()) {
                for (JsonElement op : rawInput.getAsJsonArray("ops")) {
                    if (op.isJsonObject() && op.getAsJsonObject().has("path")) {
                        String path = op.getAsJsonObject().get("path").getAsString();
                        if (path.contains("/memories/")) {
                            memoryManager.onMemoryAccessed(workspacePath, path);
                        }
                    }
                }
            } else if (rawInput.has("path")) {
                String path = rawInput.get("path").getAsString();
                if (path.contains("/memories/")) {
                    memoryManager.onMemoryAccessed(workspacePath, path);
                }
            }
        } catch (Exception e) {
            logger.debug("检测记忆访问失败", e);
        }
    }


    // ==================== Getters ====================

    public void setGlobalListener(AcpResponseListener listener) {
        if (listener != null) {
            this.globalListener = listener;
        }
    }

    public void setForceNewSession(boolean forceNewSession) {
        this.forceNewSession = forceNewSession;
    }

    public void setTargetRestoreSessionId(String targetRestoreSessionId) {
        this.targetRestoreSessionId = targetRestoreSessionId;
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
