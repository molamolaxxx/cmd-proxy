package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProviderRouter;
import com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage;
import com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import com.mola.cmd.proxy.app.acp.acpclient.listener.DefaultAcpResponseListener;
import com.mola.cmd.proxy.app.acp.acpclient.listener.LifecycleGuardedAcpResponseListener;
import com.mola.cmd.proxy.app.acp.schedule.ScheduleContextInjector;
import com.mola.cmd.proxy.app.acp.schedule.ScheduleTaskManager;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;
import com.mola.cmd.proxy.app.acp.subagent.DispatchBufferFilter;
import com.mola.cmd.proxy.app.acp.subagent.SubAgentContextInjector;
import com.mola.cmd.proxy.app.acp.subagent.SubAgentDispatcher;
import com.mola.cmd.proxy.app.acp.subagent.model.SubAgentResult;
import com.mola.cmd.proxy.app.acp.subagent.model.SubAgentTask;
import com.mola.cmd.proxy.app.acp.talkto.TalkToContextInjector;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToMessage;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    /** 定时任务管理器，通过 setter 注入，未启用时为 null */
    private ScheduleTaskManager scheduleTaskManager;

    /** 定时任务上下文注入器，通过 setter 注入 */
    private ScheduleContextInjector scheduleContextInjector;

    /** 定时任务显式 owner；MAIN 与 TEAM 不再共享 robotName key。 */
    private ScheduleOwnerKey scheduleOwnerKey;

    /** TalkTo 消息投递器，通过 setter 注入，未配置通讯录时为 null */
    private TalkToDispatcher talkToDispatcher;

    /** TalkTo 上下文注入器，通过 setter 注入 */
    private TalkToContextInjector talkToContextInjector;

    /** TalkTo 专用 robot 注册表；与 subAgent registry 分离，避免 Team 白名单串扰。 */
    private Map<String, AcpRobotParam> talkToRobotRegistry;

    /** 绑定的 robot 参数，构造时传入，不可变 */
    private final AcpRobotParam robotParam;

    /** 强制创建新会话，跳过历史会话恢复（用于 clearContext 场景） */
    private boolean forceNewSession = false;

    /** 指定恢复的目标 sessionId（用于 acpRestoreSession 场景） */
    private String targetRestoreSessionId;

    /** session 加载完成时的 turn 数，用于 close 时判断是否有新对话 */
    private int initialTurnCount;

    /** Codex/Claude/Kiro 压缩完成后置为 true，下一次 prompt 完整重注入 ACP harness。 */
    private final AtomicBoolean acpHarnessReinjectionPending = new AtomicBoolean(false);

    /** 当前 provider 是否已报告上下文压缩开始。 */
    private boolean compactionInProgress;

    /**
     * 使用指定 AgentProvider 创建 AcpClient（包级私有，供未来扩展）。
     */
    AcpClient(AgentProvider agentProvider, String workspacePath, String groupId, AcpRobotParam robotParam) {
        this(agentProvider, workspacePath,
                AcpClientIdentity.main(
                        groupId,
                        robotParam != null && !robotParam.getName().isEmpty()
                                ? robotParam.getName() : groupId,
                        robotParam != null ? robotParam.getName() : null),
                robotParam);
    }

    /**
     * 使用显式 Client 身份创建，供 TEAM 等隔离作用域使用。
     */
    AcpClient(AgentProvider agentProvider, String workspacePath,
              AcpClientIdentity clientIdentity, AcpRobotParam robotParam) {
        super(agentProvider, workspacePath, clientIdentity, robotParam);
        this.robotParam = robotParam;
        this.historyManager = new ConversationHistoryManager(clientIdentity);
        this.globalListener = new DefaultAcpResponseListener(clientIdentity.getTransportGroup());
        this.mcpConfigPaths = agentProvider.getMcpConfigPaths(this.workspacePath, robotParam);
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
     * 使用显式身份创建 AcpClient。
     */
    public AcpClient(String workspacePath, AcpClientIdentity clientIdentity,
                     AcpRobotParam robotParam) {
        this(AgentProviderRouter.getInstance().resolve(
                        robotParam != null ? robotParam.getAgentProvider() : null),
                workspacePath, clientIdentity, robotParam);
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

    /**
     * 注入定时任务支持。
     */
    public void setScheduleSupport(ScheduleTaskManager taskManager,
                                   ScheduleContextInjector contextInjector) {
        setScheduleSupport(taskManager, contextInjector, null);
    }

    public void setScheduleSupport(ScheduleTaskManager taskManager,
                                   ScheduleContextInjector contextInjector,
                                   ScheduleOwnerKey ownerKey) {
        this.scheduleTaskManager = taskManager;
        this.scheduleContextInjector = contextInjector;
        this.scheduleOwnerKey = ownerKey;
        if (taskManager != null && ownerKey != null) {
            taskManager.register(ownerKey);
        }
    }

    /**
     * 注入 TalkTo 支持。
     *
     * @param dispatcher      TalkTo 消息投递器
     * @param injector        TalkTo 上下文注入器
     * @param robotRegistry   全局 robot 注册表
     */
    public void setTalkToSupport(TalkToDispatcher dispatcher,
                                 TalkToContextInjector injector,
                                 Map<String, AcpRobotParam> robotRegistry) {
        this.talkToDispatcher = dispatcher;
        this.talkToContextInjector = injector;
        this.talkToRobotRegistry = robotRegistry;
    }

    // ==================== 生命周期 ====================



    @Override
    protected void createSession() throws IOException {
        // 指定恢复目标 sessionId（acpRestoreSession 场景）
        if (targetRestoreSessionId != null) {
            try {
                loadSession(targetRestoreSessionId);
            } catch (IOException e) {
                if (isActiveSessionConflict(e)) {
                    logger.warn("目标 session 正被其他进程使用；安全策略禁止自动终止未知进程, sessionId={}",
                            targetRestoreSessionId);
                }
                throw e;
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
                    if (isActiveSessionConflict(e)) {
                        logger.warn("最近 session 正被其他进程使用；不终止占用进程，安全回退到 session/new, sessionId={}",
                                latestSessionId);
                    } else {
                        logger.warn("session/load 失败，回退到 session/new, sessionId={}", latestSessionId, e);
                    }
                    historyManager.reset();
                }
            }
        }

        newSession();
    }

    /**
     * 检查异常是否为 session 被其它进程占用。
     * <p>
     * 这里只做识别和安全回退，绝不根据错误文本中的 PID 终止未知进程。
     */
    static boolean isActiveSessionConflict(IOException e) {
        String message = e.getMessage();
        return message != null && message.contains("Session is active in another process");
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
        send(userInput, files, PromptOptions.defaults());
    }

    public void send(String userInput, List<Map<String, String>> files, PromptOptions options) {
        if (userInput == null || userInput.trim().isEmpty()) {
            globalListener.onError(new IllegalArgumentException("用户输入不能为空"));
            return;
        }

        long generation = currentLifecycleGeneration();
        if (!compareAndSetStateIfActive(generation, State.READY, State.BUSY)) {
            globalListener.onError(new IllegalStateException(
                    "当前 client 状态不允许发送消息: " + state.get()));
            return;
        }

        // 记录本轮新上传的图片路径（用于 inline image block）
        Set<String> previousFiles = new HashSet<>(historyManager.getFileAbsolutePaths());
        historyManager.saveFiles(sessionId, files);
        Set<String> newImagePaths = new LinkedHashSet<>();
        for (String path : historyManager.getFileAbsolutePaths()) {
            if (!previousFiles.contains(path) && isImageFile(path)) {
                newImagePaths.add(path);
            }
        }

        AcpResponseListener guardedListener = new LifecycleGuardedAcpResponseListener(
                globalListener, () -> isLifecycleGenerationActive(generation));
        try {
            executor.submit(() -> {
                try {
                    sendPrompt(userInput, historyManager.getFileAbsolutePaths(), newImagePaths,
                            guardedListener, options);
                    if (compareAndSetStateIfActive(generation, State.BUSY, State.READY)) {
                        // turn 结束且 generation 仍有效时才检查 inbox
                        checkAndDeliverInbox();
                    }
                } catch (Exception e) {
                    logger.error("ACP send 失败", e);
                    if (setStateIfActive(generation, State.ERROR)) {
                        guardedListener.onError(e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            if (setStateIfActive(generation, State.ERROR)) {
                guardedListener.onError(e);
            }
        }
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
        if (!beginClose()) {
            return;
        }

        try {
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
        } finally {
            closeAfterBegin();
        }
    }

    // ==================== MCP 配置加载 ====================

    private JsonArray loadMcpServersFromConfigs() {
        return McpConfigLoader.loadFromPaths(mcpConfigPaths);
    }

    // ==================== Prompt ====================


    private void sendPrompt(String userInput, Collection<String> filePaths, AcpResponseListener listener) throws IOException {
        sendPrompt(userInput, filePaths, Collections.emptySet(), listener, PromptOptions.defaults());
    }

    private void sendPrompt(String userInput, Collection<String> filePaths, Collection<String> newImagePaths, AcpResponseListener listener, PromptOptions options) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);

        // 首轮注入完整上下文；provider 压缩上下文后，也在下一次 prompt 完整重注入。
        // 正常后续 turn 仍只带时间+用户输入，保留 prompt prefix caching 的收益。
        boolean isFirstTurn = (historyManager.getTurnCount() == initialTurnCount);
        boolean reInjectAfterCompaction = acpHarnessReinjectionPending.getAndSet(false);
        boolean shouldInjectFullAcpHarness = isFirstTurn || reInjectAfterCompaction;

        StringBuilder fullTextBuilder = new StringBuilder();

        if (shouldInjectFullAcpHarness) {
            if (reInjectAfterCompaction) {
                logger.info("上下文压缩后完整重注入 ACP harness, provider={}, sessionId={}, turn={}",
                        agentProvider.getName(), sessionId, historyManager.getTurnCount());
            }
            // ==================== 全局系统指令（直接输出 JSON 规则，覆盖所有 action 类型） ====================
            fullTextBuilder.append("\n<acp-harness>\n");
            fullTextBuilder.append("以下指令由 ACP harness 注入，用于声明你可用的系统能力（子Agent派发、定时任务、团队通讯、记忆），请正常遵循。\n\n");
            fullTextBuilder.append("当你需要触发系统操作（如 dispatch_subagent、talk_to、schedule_task 等）时，");
            fullTextBuilder.append("请在回复中直接输出对应的 JSON 文本，");
            fullTextBuilder.append("不要通过 Bash echo、Write 等任何工具来发送，也不要包裹在 ``` 代码块中。");
            fullTextBuilder.append("输出 JSON 后立即结束回复，系统会自动拦截并处理。\n\n");

            // 注入子 Agent 上下文
            if (subAgentContextInjector != null && robotParam != null
                    && robotParam.hasSubAgents() && globalRobotRegistry != null) {
                try {
                    String subAgentContext = subAgentContextInjector.buildContext(
                            robotParam.getSubAgents(), globalRobotRegistry, robotParam.getName());
                    if (!subAgentContext.isEmpty()) fullTextBuilder.append(subAgentContext).append("\n");
                } catch (Exception e) {
                    logger.warn("构建子 Agent 上下文失败，跳过", e);
                }
            }

            // 注入定时任务上下文
            if (scheduleContextInjector != null) {
                try {
                    boolean scheduleEnabled = robotParam == null || robotParam.isScheduleEnabled();
                    String scheduleContext = scheduleContextInjector.buildContext(scheduleEnabled, options.isScheduleExecution());
                    if (!scheduleContext.isEmpty()) fullTextBuilder.append(scheduleContext).append("\n");
                } catch (Exception e) {
                    logger.warn("构建定时任务上下文失败，跳过", e);
                }
            }

            // 注入 TalkTo 通讯录上下文
            if (talkToContextInjector != null && robotParam != null
                    && talkToRobotRegistry != null) {
                try {
                    String talkToContext = talkToContextInjector.buildContext(
                            robotParam.getContacts(), talkToRobotRegistry, robotParam.getName());
                    if (!talkToContext.isEmpty()) fullTextBuilder.append(talkToContext).append("\n");
                } catch (Exception e) {
                    logger.warn("构建 TalkTo 上下文失败，跳过", e);
                }
            }

            // 注入记忆上下文
            if (memoryManager != null) {
                try {
                    String memoryContext = memoryManager.buildMemoryPrompt(workspacePath);
                    if (!memoryContext.isEmpty()) fullTextBuilder.append(memoryContext).append("\n");
                } catch (Exception e) {
                    logger.warn("构建记忆上下文失败，跳过", e);
                }
            }

            fullTextBuilder.append("</acp-harness>\n");
        }

        // 文件路径每次都带
        if (filePaths != null && !filePaths.isEmpty()) {
            StringBuilder fb = new StringBuilder("[Attached Files]\n");
            for (String path : filePaths) {
                fb.append("- ").append(path).append("\n");
            }
            fullTextBuilder.append(fb).append("\n");
        }

        // 时间上下文每次都带（定时任务等场景需要精确时间）
        String timeContext = String.format("[Current Time: %s]\n[Workspace: %s]\n",
                ZonedDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z (EEEE)")),
                workspacePath);
        fullTextBuilder.append(timeContext).append(userInput);

        JsonArray prompt = new JsonArray();
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", fullTextBuilder.toString());
        prompt.add(textBlock);

        // Claude provider: 将本轮新上传的图片作为 image content block 内嵌
        if (agentProvider.needsInlineImages() && newImagePaths != null && !newImagePaths.isEmpty()) {
            for (String imgPath : newImagePaths) {
                try {
                    byte[] imgBytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(imgPath));
                    String base64Data = Base64.getEncoder().encodeToString(imgBytes);
                    JsonObject imageBlock = new JsonObject();
                    imageBlock.addProperty("type", "image");
                    imageBlock.addProperty("data", base64Data);
                    imageBlock.addProperty("mimeType", detectImageMediaType(imgPath));
                    prompt.add(imageBlock);
                    logger.info("已内嵌图片 image block: {}", imgPath);
                } catch (IOException e) {
                    logger.warn("读取图片文件失败，跳过内嵌: {}", imgPath, e);
                }
            }
        }

        params.add("prompt", prompt);

        JsonObject request = buildRequest("session/prompt", params);
        String requestId = request.get("id").getAsString();

        // 使用后台线程写入 stdin，避免 Windows 管道死锁
        // 当请求体很大时（base64 图片 + 系统上下文），同步写入可能阻塞在 pipe buffer 满，
        // 而子进程可能同时尝试写 stdout（如 metadata 通知），形成双向死锁。
        // 将写入放到后台线程，当前线程立即开始读 stdout，保持 stdout pipe 畅通。
        AtomicReference<IOException> stdinWriteError = new AtomicReference<>();
        sendJsonInBackground(request, stdinWriteError);

        historyManager.addUserMessage(userInput);

        // 流式读取
        StringBuilder fullResponse = new StringBuilder();
        // 缓存 toolCallId → title，防止后续 update 中 title 为空
        Map<String, String> toolTitleCache = new HashMap<>();
        // 缓冲过滤器：拦截 dispatch_subagent / schedule_task / manage_schedule / talk_to JSON，避免推送给用户
        boolean scheduleFilterEnabled = scheduleTaskManager != null;
        boolean talkToFilterEnabled = talkToDispatcher != null;
        DispatchBufferFilter bufferFilter = new DispatchBufferFilter(
                listener, subAgentDispatcher != null, scheduleFilterEnabled, talkToFilterEnabled);
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                IOException writeErr = stdinWriteError.get();
                if (writeErr != null) {
                    listener.onError(new IOException("ACP stdin 写入失败: " + writeErr.getMessage(), writeErr));
                } else {
                    listener.onError(new IOException("ACP 进程意外关闭"));
                }
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

            AgentProvider.CompactionSignal compactionSignal = observeCompactionSignal(msg, listener);

            // prompt response（JSON-RPC Response 没有 method 字段，排除 Request 误匹配）
            if (!msg.has("method") && msg.has("id") && requestId.equals(msg.get("id").getAsString())) {
                String stopReason = "unknown";
                if (msg.has("result") && msg.getAsJsonObject("result").has("stopReason")) {
                    stopReason = msg.getAsJsonObject("result").get("stopReason").getAsString();
                }
                logger.info("ACP prompt turn 结束, stopReason={}, msg = {}", stopReason, trimmed);

                // 排空迟到 chunk（OpenCode ACP bug workaround）
                // sleep 让管道里迟到的数据到位，然后一次抽干 reader 缓冲区
                drainLateChunks(fullResponse, bufferFilter, listener, toolTitleCache);

                historyManager.addAssistantMessage(fullResponse.toString());
                historyManager.flushTurn(sessionId);

                // flush 缓冲区（如果有未推送的非 dispatch 内容）
                bufferFilter.flush();

                // 如果 bufferFilter 已捕获指令 JSON，直接用捕获的 JSON 分发执行
                List<String> capturedJsonList = bufferFilter.getCapturedJsonList();
                if (!capturedJsonList.isEmpty()) {
                    handleCapturedActions(capturedJsonList, fullResponse.toString(), listener);
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
                processSessionUpdate(msg, fullResponse, bufferFilter, listener, toolTitleCache);
            } else {
                double usage = agentProvider.extractContextUsage(msg);
                if (usage >= 0) {
                    contextUsagePercentage = usage;
                } else if (compactionSignal != AgentProvider.CompactionSignal.NONE) {
                    // Kiro 的压缩状态属于自定义 notification，不是标准 session/update。
                    // 信号已处理，无需再记录为未匹配输出。
                } else {
                    logger.warn("ACP 输出未匹配任何处理分支, msg={}", msg);
                }
            }
        }
    }

    /**
     * 处理 session/update 通知。
     * 主循环和 drain 阶段共用此方法。
     */
    private void processSessionUpdate(JsonObject msg, StringBuilder fullResponse,
                                      DispatchBufferFilter bufferFilter, AcpResponseListener listener,
                                      Map<String, String> toolTitleCache) {
        JsonObject updateParams = msg.getAsJsonObject("params");
        if (updateParams == null) return;
        JsonObject update = updateParams.getAsJsonObject("update");
        if (update == null) return;

        String updateType = update.has("sessionUpdate")
                ? update.get("sessionUpdate").getAsString() : "";

        if ("agent_message_chunk".equals(updateType)) {
            JsonObject content = update.getAsJsonObject("content");
            if (content != null && content.has("text")) {
                String text = content.get("text").getAsString();
                fullResponse.append(text);
                bufferFilter.accept(text);
            }
        } else if ("tool_call".equals(updateType) || "tool_call_update".equals(updateType)) {
            String toolCallId = update.has("toolCallId") ? update.get("toolCallId").getAsString() : "";
            String title = update.has("title") ? update.get("title").getAsString() : "";
            String status = update.has("status") ? update.get("status").getAsString() : "pending";
            if (!title.isEmpty()) {
                toolTitleCache.put(toolCallId, title);
            } else {
                title = toolTitleCache.getOrDefault(toolCallId, "");
            }
            JsonObject updateLog = update.deepCopy();
            updateLog.remove("rawInput");
            updateLog.remove("rawOutput");
            updateLog.remove("_meta");
            logger.info("工具调用: {}", updateLog);
            if ("completed".equals(status)) {
                JsonElement rawInputEl = update.get("rawInput");
                JsonElement rawOutputEl = update.get("rawOutput");
                JsonObject rawInput = (rawInputEl != null && rawInputEl.isJsonObject())
                        ? rawInputEl.getAsJsonObject() : null;
                JsonObject rawOutput = (rawOutputEl != null && rawOutputEl.isJsonObject())
                        ? rawOutputEl.getAsJsonObject() : null;
                historyManager.addToolMessage(toolCallId, title, status, rawInput, rawOutput);
                detectMemoryAccess(rawInput);
            }
            listener.onToolCall(toolCallId, title, status, update);
        } else if ("usage_update".equals(updateType)) {
            if (update.has("used") && update.has("size")) {
                double used = update.get("used").getAsDouble();
                double size = update.get("size").getAsDouble();
                if (size > 0) {
                    contextUsagePercentage = (used / size) * 100;
                }
            }
        } else if ("agent_thought_chunk".equals(updateType)) {
            // 不处理思考
        } else {
            logger.warn("ACP IN session/update 输出未匹配任何处理分支, msg={}", msg);
        }
    }

    /**
     * 消费 provider 专用的上下文压缩事件，并在完成时安排下一次 prompt 的完整 Harness 重注入。
     */
    private AgentProvider.CompactionSignal observeCompactionSignal(JsonObject msg, AcpResponseListener listener) {
        if (msg.has("method")
                && "_kiro.dev/compaction/status".equals(msg.get("method").getAsString())) {
            // Kiro 的扩展文档未固定公开 payload schema。保留完整报文日志，既便于
            // 验证 status 字段映射，也能在出现 sessionId 迁移时及时发现。
            logger.info("Kiro 上下文压缩通知: {}", msg);
        }
        AgentProvider.CompactionSignal signal = agentProvider.detectCompactionSignal(msg);
        switch (signal) {
            case STARTED:
                compactionInProgress = true;
                logger.info("Agent 开始压缩上下文, provider={}, sessionId={}",
                        agentProvider.getName(), sessionId);
                break;
            case COMPLETED:
                compactionInProgress = false;
                acpHarnessReinjectionPending.set(true);
                if (listener != null) {
                    listener.onCompactionEvent("COMPACTION_COMPLETED", agentProvider.getName());
                }
                logger.info("Agent 已完成上下文压缩；下一次 prompt 将完整重注入 ACP harness, provider={}, sessionId={}",
                        agentProvider.getName(), sessionId);
                break;
            case FAILED:
                compactionInProgress = false;
                logger.warn("Agent 上下文压缩失败, provider={}, sessionId={}",
                        agentProvider.getName(), sessionId);
                break;
            case CONTEXT_USAGE_REFRESHED:
                // Claude adapter 的 compact_boundary 被转换为 usage_update。普通 usage_update
                // 不触发重注入，只有已看到 Compaction STARTED 才代表压缩完成。
                if (compactionInProgress) {
                    compactionInProgress = false;
                    acpHarnessReinjectionPending.set(true);
                    if (listener != null) {
                        listener.onCompactionEvent("COMPACTION_COMPLETED", agentProvider.getName());
                    }
                    logger.info("Agent 压缩后的上下文用量已刷新；下一次 prompt 将完整重注入 ACP harness, provider={}, sessionId={}",
                            agentProvider.getName(), sessionId);
                }
                break;
            case NONE:
            default:
                break;
        }
        return signal;
    }

    /**
     * 排空迟到 chunk（OpenCode ACP bug workaround：session/update 通知可能在
     * end_turn RPC response 之后送达）。
     * sleep 让管道里迟到的数据到位，然后一次性抽干 reader 缓冲区。
     */
    private void drainLateChunks(StringBuilder fullResponse, DispatchBufferFilter bufferFilter,
                                 AcpResponseListener listener, Map<String, String> toolTitleCache)
            throws IOException {
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

            observeCompactionSignal(msg, listener);

            if (msg.has("method") && "session/update".equals(msg.get("method").getAsString())) {
                processSessionUpdate(msg, fullResponse, bufferFilter, listener, toolTitleCache);
            } else if (msg.has("method") && "session/request_permission".equals(msg.get("method").getAsString())) {
                autoAllowPermission(msg);
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
     * 检测并处理定时任务指令（schedule_task / manage_schedule）。
     *
     * @return true 如果检测到并处理了定时任务指令
     */
    private boolean handleScheduleAction(String fullResponse, AcpResponseListener listener) {
        if (scheduleTaskManager == null) return false;

        try {
            String robotName = robotParam != null ? robotParam.getName() : groupId;
            ScheduleOwnerKey owner = scheduleOwnerKey != null
                    ? scheduleOwnerKey : ScheduleOwnerKey.main(robotName);
            String resultText = scheduleTaskManager.detectAndHandle(fullResponse, owner);
            if (resultText == null) return false;

            // UI 事件推送：根据 action 类型决定展开/收起
            boolean isCreate = fullResponse.contains("schedule_task");
            String eventType = isCreate ? "SCHEDULE_CREATE" : "SCHEDULE_MANAGE";
            listener.onScheduleEvent(eventType, resultText, isCreate);

            logger.info("定时任务指令处理完成");
            sendPrompt(resultText, null, listener);
            return true;

        } catch (Exception e) {
            logger.error("定时任务指令处理失败", e);
            try {
                sendPrompt("[定时任务操作结果]\n操作失败: " + e.getMessage(), null, listener);
            } catch (IOException ioe) {
                logger.error("发送错误结果失败", ioe);
                listener.onComplete(fullResponse);
            }
            return true;
        }
    }



    /**
     * 检测并处理 talkTo 指令。
     * <p>
     * 在主 Agent turn 结束后调用。如果检测到 talk_to 指令：
     * 1. 解析目标和内容
     * 2. 通过 TalkToDispatcher 投递消息
     * 3. 将结果作为 follow-up prompt 发回主 Agent
     *
     * @return true 如果检测到并处理了 talkTo 指令
     */
    private boolean handleTalkTo(String fullResponse, AcpResponseListener listener) {
        if (talkToDispatcher == null) return false;

        TalkToRequest request =
                talkToDispatcher.detectTalkTo(fullResponse);
        if (request == null) return false;

        String senderName = talkToRoutingName();
        // 从 groupId 中提取 chatterId（groupId = sort(chatterId, acpId).join("")）
        String senderChatterId = extractChatterId();
        logger.info("检测到 talkTo 指令: {} → {}", senderName, request.getTarget());

        try {
            java.util.List<com.mola.cmd.proxy.app.acp.talkto.model.ContactRef> contacts =
                    robotParam != null ? robotParam.getContacts() : null;
            String resultText = talkToDispatcher.deliver(request, senderName, senderChatterId, contacts);
            // 在发送方前端推送 talkTo 卡片
            if (!talkToDispatcher.managesTalkToEvents()) {
                listener.onTalkToEvent("TALK_TO_SEND", request.getTarget(), request.getContent());
            }
            sendPrompt(resultText, null, listener);
            return true;
        } catch (Exception e) {
            logger.error("talkTo 处理失败", e);
            try {
                sendPrompt("[talkTo 结果]\n发送失败: " + e.getMessage(), null, listener);
            } catch (IOException ioe) {
                logger.error("发送 talkTo 错误结果失败", ioe);
                listener.onComplete(fullResponse);
            }
            return true;
        }
    }

    /**
     * 处理捕获的多个指令 JSON，按类型分组执行。
     * - 多个 talk_to：批量 deliver，合并结果后一次 sendPrompt
     * - 多个 dispatch_subagent：合并处理
     * - 多个 schedule_task/manage_schedule：逐个处理
     * - 混合类型：按类型分组，各自走各自的流程
     */
    private void handleCapturedActions(List<String> capturedJsonList, String fullResponse, AcpResponseListener listener) {
        List<String> talkToJsons = new ArrayList<>();
        List<String> dispatchJsons = new ArrayList<>();
        List<String> scheduleJsons = new ArrayList<>();
        List<String> unknownJsons = new ArrayList<>();

        for (String json : capturedJsonList) {
            if (json.contains("\"action\":\"talk_to\"")) {
                talkToJsons.add(json);
            } else if (json.contains("\"action\":\"dispatch_subagent\"")) {
                dispatchJsons.add(json);
            } else if (json.contains("\"action\":\"schedule_task\"")
                    || json.contains("\"action\":\"manage_schedule\"")) {
                scheduleJsons.add(json);
            } else {
                unknownJsons.add(json);
            }
        }

        boolean anyHandled = false;

        // 处理 dispatch_subagent（多个合并为一次派发）
        if (!dispatchJsons.isEmpty()) {
            anyHandled |= handleSubAgentDispatch(fullResponse, listener);
        }

        // 处理 schedule_task / manage_schedule
        if (!scheduleJsons.isEmpty()) {
            anyHandled |= handleScheduleAction(fullResponse, listener);
        }

        // 处理 talk_to（批量 deliver，合并结果）
        if (!talkToJsons.isEmpty()) {
            anyHandled |= handleTalkToBatch(talkToJsons, fullResponse, listener);
        }

        for (String json : unknownJsons) {
            logger.warn("捕获了未知 action 的 JSON: {}", json);
        }

        if (!anyHandled) {
            logger.warn("所有 action 处理失败，回退到正常 complete, count={}", capturedJsonList.size());
            listener.onComplete(fullResponse);
        }
    }

    /**
     * 根据捕获的 JSON 中的 action 类型分发执行对应的业务逻辑。
     * 在 turn 结束后调用，替代原来的 if-return 重解析链。
     */
    private void handleCapturedAction(String capturedJson, String fullResponse, AcpResponseListener listener) {
        boolean handled = false;
        // 用 "action" 字段的精确值做路由，避免 content 内容中的关键词串台
        if (capturedJson.contains("\"action\":\"dispatch_subagent\"")) {
            handled = handleSubAgentDispatch(fullResponse, listener);
        } else if (capturedJson.contains("\"action\":\"schedule_task\"")
                || capturedJson.contains("\"action\":\"manage_schedule\"")) {
            handled = handleScheduleAction(fullResponse, listener);
        } else if (capturedJson.contains("\"action\":\"talk_to\"")) {
            handled = handleTalkToDirect(capturedJson, fullResponse, listener);
        } else {
            logger.warn("捕获了未知 action 的 JSON: {}", capturedJson);
        }

        if (!handled) {
            logger.warn("action 处理失败或未识别，回退到正常 complete, action={}",
                    capturedJson.length() > 100 ? capturedJson.substring(0, 100) : capturedJson);
            listener.onComplete(fullResponse);
        }
    }

    /**
     * 直接使用已捕获的 JSON 处理 talk_to，避免从 fullResponse 中重新解析。
     */
    private boolean handleTalkToDirect(String capturedJson, String fullResponse, AcpResponseListener listener) {
        if (talkToDispatcher == null) {
            logger.warn("talkToDispatcher 为 null，无法处理 talk_to");
            return false;
        }

        TalkToRequest request = talkToDispatcher.parseTalkToJson(capturedJson);
        if (request == null) {
            logger.warn("capturedJson 解析 talk_to 失败, json={}", capturedJson);
            return false;
        }

        String senderName = talkToRoutingName();
        String senderChatterId = extractChatterId();
        logger.info("检测到 talkTo 指令(buffered): {} → {}", senderName, request.getTarget());

        try {
            java.util.List<com.mola.cmd.proxy.app.acp.talkto.model.ContactRef> contacts =
                    robotParam != null ? robotParam.getContacts() : null;
            String resultText = talkToDispatcher.deliver(request, senderName, senderChatterId, contacts);
            if (!talkToDispatcher.managesTalkToEvents()) {
                listener.onTalkToEvent("TALK_TO_SEND", request.getTarget(), request.getContent());
            }
            sendPrompt(resultText, null, listener);
            return true;
        } catch (Exception e) {
            logger.error("talkTo 处理失败", e);
            try {
                sendPrompt("[talkTo 结果]\n发送失败: " + e.getMessage(), null, listener);
            } catch (IOException ioe) {
                logger.error("发送 talkTo 错误结果失败", ioe);
            }
            return true; // 已处理（虽然失败），不 fallback
        }
    }

    /**
     * 批量处理多个 talk_to 指令：逐个 deliver，合并结果后一次 sendPrompt。
     */
    private boolean handleTalkToBatch(List<String> talkToJsons, String fullResponse, AcpResponseListener listener) {
        if (talkToDispatcher == null) {
            logger.warn("talkToDispatcher 为 null，无法处理 talk_to batch");
            return false;
        }

        String senderName = talkToRoutingName();
        String senderChatterId = extractChatterId();
        java.util.List<com.mola.cmd.proxy.app.acp.talkto.model.ContactRef> contacts =
                robotParam != null ? robotParam.getContacts() : null;

        StringBuilder combinedResult = new StringBuilder();
        int successCount = 0;
        int failCount = 0;

        for (String json : talkToJsons) {
            TalkToRequest request = talkToDispatcher.parseTalkToJson(json);
            if (request == null) {
                logger.warn("批量 talk_to 解析失败, json={}", json);
                combinedResult.append("[talkTo → ?] 解析失败\n");
                failCount++;
                continue;
            }

            logger.info("批量 talkTo 指令: {} → {}", senderName, request.getTarget());
            try {
                String resultText = talkToDispatcher.deliver(request, senderName, senderChatterId, contacts);
                if (!talkToDispatcher.managesTalkToEvents()) {
                    listener.onTalkToEvent("TALK_TO_SEND", request.getTarget(), request.getContent());
                }
                combinedResult.append(resultText).append("\n");
                successCount++;
            } catch (Exception e) {
                logger.error("批量 talkTo 处理失败: target={}", request.getTarget(), e);
                combinedResult.append("[talkTo → ").append(request.getTarget())
                        .append("] 发送失败: ").append(e.getMessage()).append("\n");
                failCount++;
            }
        }

        logger.info("批量 talk_to 完成: success={}, fail={}, total={}", successCount, failCount, talkToJsons.size());

        try {
            sendPrompt(combinedResult.toString().trim(), null, listener);
        } catch (IOException e) {
            logger.error("发送批量 talkTo 合并结果失败", e);
        }
        return true;
    }

    /**
     * 检查并投递 inbox 中的待处理消息。
     * 在 turn 结束 state 变为 READY 后调用。
     */
    private void checkAndDeliverInbox() {
        if (talkToDispatcher == null) return;
        String robotName = talkToRoutingName();
        if (robotName == null || robotName.isEmpty()) return;

        TalkToMessage pending = talkToDispatcher.pollInbox(robotName);
        if (pending != null) {
            logger.info("从 inbox 投递消息: from={}, to={}", pending.getSender(), robotName);
            // 先推送来信卡片到前端
            talkToDispatcher.pushIncomingMessageCard(this, pending);
            // 再发送消息，会再次进入 BUSY 状态
            send(pending.buildPrompt(), null);
        }
    }

    /**
     * 普通 client 沿用 robotName；Team client 必须使用不可变 teamMemberId，
     * 防止同一来源 robot 被多个 Team/member 复用时发生路由冲突。
     */
    private String talkToRoutingName() {
        if (clientIdentity != null && clientIdentity.isTeam()) {
            return clientIdentity.getTeamMemberId();
        }
        return robotParam != null ? robotParam.getName() : groupId;
    }


    /**
     * 检测工具调用是否读取了记忆明细文件，触发访问强化。
     if (braceStart < 0) return null;

     int braces = 0;
     boolean inString = false;
     boolean escaped = false;

     for (int i = braceStart; i < fullResponse.length(); i++) {
     char c = fullResponse.charAt(i);
     if (escaped) { escaped = false; continue; }
     if (c == '\\' && inString) { escaped = true; continue; }
     if (c == '"') { inString = !inString; continue; }
     if (inString) continue;

     if (c == '{') braces++;
     else if (c == '}') {
     braces--;
     if (braces == 0) {
     return fullResponse.substring(braceStart, i + 1);
     }
     }
     }
     return null;
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

    /**
     * 从 groupId 中提取 chatterId。
     * groupId = sort(chatterId, acpId).join("")，acpId 以 "acp-" 开头。
     * 通过去掉 acpId 部分得到 chatterId。
     */
    private String extractChatterId() {
        if (groupId == null || groupId.isEmpty()) return "";
        // acpId 格式: "acp-" + robotName.replace(" ", "_")
        String acpId = "";
        if (robotParam != null && robotParam.getName() != null) {
            acpId = "acp-" + robotParam.getName().replace(" ", "_").replace("\u3000", "_");
        }
        if (acpId.isEmpty()) return groupId;
        // groupId 是 sort(chatterId, acpId) 后拼接的，去掉 acpId 部分就是 chatterId
        if (groupId.startsWith(acpId)) {
            return groupId.substring(acpId.length());
        } else if (groupId.endsWith(acpId)) {
            return groupId.substring(0, groupId.length() - acpId.length());
        }
        return groupId;
    }

    // ==================== 图片工具方法 ====================

    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg"));

    private static boolean isImageFile(String path) {
        if (path == null) return false;
        int dot = path.lastIndexOf('.');
        if (dot < 0) return false;
        return IMAGE_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase());
    }

    private static String detectImageMediaType(String path) {
        String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
        switch (ext) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "svg": return "image/svg+xml";
            default: return "application/octet-stream";
        }
    }
}
