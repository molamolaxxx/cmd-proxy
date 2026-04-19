package com.mola.cmd.proxy.app.acp.subagent;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.MemoryManagerBridge;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import com.mola.cmd.proxy.app.acp.subagent.model.SubAgentResult;
import com.mola.cmd.proxy.app.acp.subagent.model.SubAgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 子 Agent 派发器，负责：
 * <ol>
 *   <li>从主 Agent LLM 输出中检测 dispatch_subagent 指令</li>
 *   <li>并行创建 SubAgentAcpClient 执行子任务</li>
 *   <li>聚合结果，格式化为主 Agent 可理解的上下文</li>
 * </ol>
 */
public class SubAgentDispatcher implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(SubAgentDispatcher.class);

    private static final Pattern DISPATCH_PATTERN = Pattern.compile(
            "\\{\\s*\"action\"\\s*:\\s*\"dispatch_subagent\".*?\"tasks\"\\s*:\\s*\\[.*?]\\s*}",
            Pattern.DOTALL);

    private final Map<String, AcpRobotParam> robotRegistry;
    private final Set<String> allowedAgentNames;
    private final int defaultTimeoutSeconds;
    private final ExecutorService dispatchPool;

    /** 正在运行的子 Agent Client，用于 cancelAll 时逐个关闭 */
    private final java.util.concurrent.ConcurrentLinkedQueue<SubAgentAcpClient> activeClients =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 取消标志，cancelAll 后置为 true，阻止后续 follow-up prompt */
    private volatile boolean cancelled = false;

    /** robot name -> MemoryManagerBridge，由 AcpProxy 注入，未启用记忆的 robot 不在此 map 中 */
    private Map<String, MemoryManagerBridge> memoryManagers = Collections.emptyMap();

    public SubAgentDispatcher(Map<String, AcpRobotParam> robotRegistry,
                              Set<String> allowedAgentNames,
                              int defaultTimeoutSeconds) {
        this.robotRegistry = robotRegistry;
        this.allowedAgentNames = allowedAgentNames;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.dispatchPool = new ThreadPoolExecutor(
                5, 10, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10),
                r -> {
                    Thread t = new Thread(r, "subagent-dispatch");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy()
        );
    }

    /**
     * 注入子 Agent 的记忆管理器映射。
     * key 为 robot name，value 为对应的 MemoryManagerBridge。
     */
    public void setMemoryManagers(Map<String, MemoryManagerBridge> memoryManagers) {
        this.memoryManagers = memoryManagers != null ? memoryManagers : Collections.emptyMap();
    }

    // ==================== 指令检测 ====================

    /**
     * 检测 LLM 输出中是否包含 dispatch_subagent 指令。
     *
     * @param fullResponse 主 Agent 当前累积的完整输出
     * @return 解析出的任务列表，未检测到时返回 null
     */
    public List<SubAgentTask> detectDispatch(String fullResponse) {
        Matcher matcher = DISPATCH_PATTERN.matcher(fullResponse);
        if (!matcher.find()) return null;

        try {
            String json = matcher.group();
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonArray tasksArray = obj.getAsJsonArray("tasks");
            if (tasksArray == null || tasksArray.size() == 0) return null;

            List<SubAgentTask> tasks = new ArrayList<>();
            for (JsonElement elem : tasksArray) {
                JsonObject taskObj = elem.getAsJsonObject();
                String agent = taskObj.has("agent") ? taskObj.get("agent").getAsString() : null;
                String title = taskObj.has("title") ? taskObj.get("title").getAsString() : null;
                String prompt = taskObj.has("prompt") ? taskObj.get("prompt").getAsString() : null;
                if (agent != null && prompt != null && !agent.isEmpty() && !prompt.isEmpty()) {
                    tasks.add(new SubAgentTask(agent, title, prompt));
                }
            }
            return tasks.isEmpty() ? null : tasks;
        } catch (Exception e) {
            logger.warn("dispatch_subagent JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 并行派发 ====================

    /**
     * 并行派发子 Agent 任务并聚合结果。
     *
     * @param tasks           主 Agent LLM 发出的任务列表
     * @param listener        用于向用户推送执行状态
     * @param callerWorkspace 主 Agent 的 workspacePath（兜底用）
     * @return 聚合后的结果列表
     */
    public List<SubAgentResult> dispatch(List<SubAgentTask> tasks,
                                         AcpResponseListener listener,
                                         String callerWorkspace) {
        // 重置取消标志
        cancelled = false;

        // 1. 校验白名单
        for (SubAgentTask task : tasks) {
            if (!allowedAgentNames.contains(task.getAgent())) {
                logger.warn("子 Agent '{}' 不在白名单中，拒绝派发", task.getAgent());
                SubAgentResult denied = new SubAgentResult();
                denied.setAgent(task.getAgent());
                denied.setStatus(SubAgentResult.Status.ERROR);
                denied.setErrorMessage("子 Agent '" + task.getAgent() + "' 未在 subAgents 配置中");
                return Collections.singletonList(denied);
            }
        }

        // 2. 通知用户：派发开始
        String taskSummary = tasks.stream()
                .map(t -> "[" + t.getDisplayName() + "] " + truncate(t.getPrompt(), 100))
                .collect(Collectors.joining("\n"));
        listener.onSubAgentEvent("DISPATCH_START", null,
                "正在派发 " + tasks.size() + " 个子 Agent 任务：\n" + taskSummary);

        // 3. 并行执行
        List<CompletableFuture<SubAgentResult>> futures = tasks.stream()
                .map(task -> CompletableFuture.supplyAsync(
                        () -> executeOne(task, listener, callerWorkspace), dispatchPool))
                .collect(Collectors.toList());

        // 4. 等待所有完成（总超时 = 单个超时 × 2，至少 60s）
        long totalTimeoutSeconds = Math.max(60, (long) defaultTimeoutSeconds * 2);
        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
        try {
            allDone.get(totalTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warn("子 Agent 整体超时（{}s），返回已完成的结果", totalTimeoutSeconds);
            listener.onSubAgentEvent("DISPATCH_COMPLETE", null,
                    "部分子 Agent 超时，返回已完成的结果");
        } catch (Exception e) {
            logger.error("子 Agent 派发异常", e);
            listener.onSubAgentEvent("DISPATCH_COMPLETE", null,
                    "子 Agent 执行异常: " + e.getMessage());
        }

        // 5. 收集结果
        List<SubAgentResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<SubAgentResult> f = futures.get(i);
            if (f.isDone() && !f.isCompletedExceptionally()) {
                results.add(f.join());
            } else {
                SubAgentResult timeout = new SubAgentResult();
                timeout.setAgent(tasks.get(i).getAgent());
                timeout.setStatus(SubAgentResult.Status.TIMEOUT);
                timeout.setErrorMessage("执行超时或异常");
                results.add(timeout);
            }
        }

        long totalSuccess = results.stream()
                .filter(r -> r.getStatus() == SubAgentResult.Status.SUCCESS).count();

        // 如果已被取消，不返回结果，抛异常阻止 follow-up prompt
        if (cancelled) {
            logger.info("子 Agent 派发已被取消，不返回结果");
            throw new java.util.concurrent.CancellationException("子 Agent 派发已取消");
        }

        listener.onSubAgentEvent("DISPATCH_COMPLETE", null,
                "所有子 Agent 已完成，成功 " + totalSuccess + "/" + results.size());
        return results;
    }

    /**
     * 执行单个子 Agent 任务（在线程池中运行）。
     */
    private SubAgentResult executeOne(SubAgentTask task,
                                      AcpResponseListener listener,
                                      String callerWorkspace) {
        long startTime = System.currentTimeMillis();
        String displayName = task.getDisplayName();
        SubAgentResult result = new SubAgentResult();
        result.setAgent(displayName);

        AcpRobotParam targetRobot = robotRegistry.get(task.getAgent());
        if (targetRobot == null) {
            result.setStatus(SubAgentResult.Status.ERROR);
            result.setErrorMessage("目标 robot 配置不存在: " + task.getAgent());
            listener.onSubAgentEvent("AGENT_ERROR", displayName,
                    "目标 robot 配置不存在");
            return result;
        }

        String workDir = (targetRobot.getWorkDir() != null && !targetRobot.getWorkDir().isEmpty())
                ? targetRobot.getWorkDir() : callerWorkspace;

        listener.onSubAgentEvent("AGENT_START", displayName,
                truncate(task.getPrompt(), 400));

        String groupId = "subagent__" + task.getAgent().hashCode()
                + "__" + System.currentTimeMillis();

        try (SubAgentAcpClient client = new SubAgentAcpClient(
                workDir, groupId, defaultTimeoutSeconds,
                targetRobot.getAgentProvider())) {

            activeClients.add(client);

            // 设置进度快照回调，每 30s 推送一次执行进度
            client.setProgressCallback(
                    snapshot -> listener.onSubAgentEvent("AGENT_PROGRESS", displayName, snapshot),
                    30_000);

            client.start();

            // 构建完整 prompt：记忆上下文 + 原始任务
            String fullPrompt = buildSubAgentPrompt(task, targetRobot, workDir);
            String response = client.sendPromptSync(fullPrompt);

            result.setStatus(SubAgentResult.Status.SUCCESS);
            result.setResponse(response);
            result.setDurationMs(System.currentTimeMillis() - startTime);

            listener.onSubAgentEvent("AGENT_COMPLETE", displayName,
                    String.format("耗时 %.1fs\n%s",
                            result.getDurationMs() / 1000.0, response));

        } catch (Exception e) {
            result.setStatus(SubAgentResult.Status.ERROR);
            result.setErrorMessage(e.getMessage());
            result.setDurationMs(System.currentTimeMillis() - startTime);

            listener.onSubAgentEvent("AGENT_ERROR", displayName,
                    e.getMessage());
            logger.error("子 Agent '{}' 执行失败", displayName, e);
        } finally {
            // try-with-resources 已关闭 client，从追踪列表移除
            activeClients.removeIf(c -> c.getGroupId().equals(groupId));
        }
        return result;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /**
     * 为子 Agent 构建完整 prompt，注入记忆上下文（如果该 robot 启用了记忆）。
     */
    private String buildSubAgentPrompt(SubAgentTask task, AcpRobotParam targetRobot, String workDir) {
        StringBuilder sb = new StringBuilder();

        // 注入记忆上下文
        MemoryManagerBridge memMgr = memoryManagers.get(targetRobot.getName());
        if (memMgr != null) {
            try {
                String memoryPrompt = memMgr.buildMemoryPrompt(workDir);
                if (memoryPrompt != null && !memoryPrompt.isEmpty()) {
                    sb.append(memoryPrompt).append("\n");
                }
            } catch (Exception e) {
                logger.warn("构建子 Agent '{}' 记忆上下文失败，跳过", task.getAgent(), e);
            }
        }

        // 注入当前时间和工作路径
        sb.append(String.format("[Current Time: %s]\n[Workspace: %s]\n",
                java.time.ZonedDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z (EEEE)")),
                workDir));

        sb.append(task.getPrompt());
        return sb.toString();
    }

    // ==================== 结果格式化 ====================

    /**
     * 将子 Agent 结果格式化为主 Agent 可理解的上下文文本。
     * 作为 follow-up prompt 注入，触发主 Agent 第二轮推理汇总。
     */
    public static String formatResults(List<SubAgentResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Sub-Agent Results]\n");
        sb.append("以下是子 Agent 的执行结果，请基于这些结果为用户提供综合回答。\n\n");

        for (SubAgentResult r : results) {
            sb.append("### ").append(r.getAgent()).append("\n");
            sb.append("状态: ").append(r.getStatus()).append("\n");
            if (r.getStatus() == SubAgentResult.Status.SUCCESS) {
                sb.append("结果:\n").append(r.getResponse()).append("\n");
            } else {
                sb.append("错误: ").append(r.getErrorMessage()).append("\n");
            }
            sb.append("耗时: ").append(r.getDurationMs()).append("ms\n\n");
        }

        sb.append("请综合以上子 Agent 的结果，为用户提供完整、连贯的回答。\n");
        return sb.toString();
    }

    @Override
    public void close() {
        cancelAll();
    }

    /**
     * 取消所有正在运行的子 Agent。
     * 关闭线程池 + 逐个关闭活跃的子 Agent 子进程。
     */
    public void cancelAll() {
        logger.info("取消所有子 Agent，活跃数={}", activeClients.size());
        cancelled = true;
        for (SubAgentAcpClient client : activeClients) {
            try {
                client.close();
            } catch (Exception e) {
                logger.warn("关闭子 Agent Client 失败: {}", e.getMessage());
            }
        }
        activeClients.clear();
    }
}
