package com.mola.cmd.proxy.app.acp.schedule;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleConfig;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduledTask;
import com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelDeliveryContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定时任务管理器。
 * <p>
 * 职责：
 * <ul>
 *   <li>任务持久化（读写 tasks.json）</li>
 *   <li>调度线程（每分钟扫描触发）</li>
 *   <li>任务 CRUD（创建、查询、取消、更新）</li>
 *   <li>触发执行（通过回调接口通知 AcpClient）</li>
 * </ul>
 */
public class ScheduleTaskManager {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleTaskManager.class);

    private static final String SCHEDULES_BASE_DIR =
            com.mola.cmd.proxy.app.utils.CmdProxyHome.pathOf("schedules");
    private static final String TASKS_FILE = "tasks.json";
    private static final String GROUP_SESSIONS_FILE = "groups.json";
    // Legacy MAIN is depth 1, Team depth 3, surface-aware MAIN depth 5.
    private static final int MAX_SCHEDULE_FILE_DEPTH = 6;

    /** 相对时间表达式：+30s, +30m, +2h, +1d */
    private static final Pattern RELATIVE_TIME_PATTERN = Pattern.compile("^\\+(\\d+)([smhd])$");
    private static final Pattern GROUP_DATE_TEMPLATE_PATTERN =
            Pattern.compile("\\{([^{}]+)}");
    private static final Pattern GROUP_DATE_FORMAT_PATTERN =
            Pattern.compile("[yMdHms._-]+");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TASK_LIST_TYPE = new TypeToken<List<ScheduledTask>>() {}.getType();
    private static final Type GROUP_SESSION_MAP_TYPE =
            new TypeToken<Map<String, String>>() {}.getType();

    private final Path schedulesBaseDir;
    private final ScheduleExecutionJournal executionJournal;

    private final CronParser cronParser = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    /** robotName -> 任务列表（内存缓存） */
    private final Map<String, List<ScheduledTask>> tasksByRobot = new ConcurrentHashMap<>();

    /** persistencePath -> (groupName -> ACP sessionId) */
    private final Map<String, Map<String, String>> groupSessionsByOwner =
            new ConcurrentHashMap<>();

    /** robotName -> 是否有任务正在执行 */
    private final Map<String, Boolean> runningByRobot = new ConcurrentHashMap<>();

    /** persistencePath -> 显式 owner 身份 */
    private final Map<String, ScheduleOwnerKey> ownerKeys = new ConcurrentHashMap<>();
    private final Set<String> blockedTeamIds = ConcurrentHashMap.newKeySet();
    private final Map<String, DeferredScheduleState> deferredSchedules =
            new ConcurrentHashMap<>();

    /** 任务触发回调 */
    private ScheduleExecutionCallback executionCallback;
    private ScopedScheduleExecutionCallback scopedExecutionCallback;

    private ScheduledExecutorService scheduler;

    public ScheduleTaskManager() {
        this(Paths.get(SCHEDULES_BASE_DIR));
    }

    /**
     * 允许测试或嵌入方显式指定持久化根目录。
     */
    public ScheduleTaskManager(Path schedulesBaseDir) {
        this.schedulesBaseDir = schedulesBaseDir.toAbsolutePath().normalize();
        this.executionJournal = new ScheduleExecutionJournal(this.schedulesBaseDir);
    }

    // ==================== 生命周期 ====================

    /**
     * 启动调度器：加载所有 robot 的任务，处理 MISSED，启动扫描线程。
     */
    public void start() {
        loadAllTasks();
        handleMissedTasks();
        startScheduler();
        logger.info("ScheduleTaskManager 启动完成，已加载 {} 个 robot 的任务",
                tasksByRobot.size());
    }

    /**
     * 停止调度器。
     */
    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void setExecutionCallback(ScheduleExecutionCallback callback) {
        this.executionCallback = callback;
    }

    public void setScopedExecutionCallback(ScopedScheduleExecutionCallback callback) {
        this.scopedExecutionCallback = callback;
    }

    // ==================== 任务管理 API ====================

    /**
     * 创建定时任务。
     *
     * @param robotName robot 名称
     * @param title     任务标题
     * @param prompt    执行内容
     * @param config    调度配置
     * @return 创建的任务
     */
    public ScheduledTask createTask(String robotName, String title, String prompt, ScheduleConfig config) {
        return createTask(ScheduleOwnerKey.main(robotName), title, prompt, config);
    }

    public ScheduledTask createTask(ScheduleOwnerKey owner, String title, String prompt,
                                    ScheduleConfig config) {
        return createTask(owner, title, prompt, config, null);
    }

    public ScheduledTask createTask(ScheduleOwnerKey owner, String title, String prompt,
                                    ScheduleConfig config, String groupName) {
        return createTask(owner, title, prompt, config, groupName, null);
    }

    public ScheduledTask createTask(ScheduleOwnerKey owner, String title, String prompt,
                                    ScheduleConfig config, String groupName,
                                    AuthPrincipalContext authPrincipalContext) {
        return createTask(owner, title, prompt, config, groupName,
                authPrincipalContext, null);
    }

    public ScheduledTask createTask(ScheduleOwnerKey owner, String title, String prompt,
                                    ScheduleConfig config, String groupName,
                                    AuthPrincipalContext authPrincipalContext,
                                    ChannelDeliveryContext channelDeliveryContext) {
        ensureOwnerWritable(owner);
        String robotName = registerOwner(owner);
        ScheduledTask task = new ScheduledTask();
        task.setId(generateId(title));
        task.setOwner(owner);
        task.setTitle(title);
        task.setPrompt(prompt);
        String normalizedGroupName = normalizeGroupName(groupName);
        validateGroupNameTemplate(normalizedGroupName);
        task.setGroupName(normalizedGroupName);
        task.setSchedule(config);
        task.setStatus(ScheduledTask.STATUS_WAITING);
        task.setCreatedAt(System.currentTimeMillis());
        task.setNextRunAt(calculateNextRunAt(config));
        task.setAuthPrincipalContext(authPrincipalContext);
        task.setChannelDeliveryContext(channelDeliveryContext);

        List<ScheduledTask> tasks = tasksByRobot.computeIfAbsent(robotName, k -> new ArrayList<>());
        synchronized (tasks) {
            tasks.add(task);
        }
        persistTasks(robotName);

        logger.info("定时任务创建成功, robot={}, id={}, title={}, nextRunAt={}",
                robotName, task.getId(), title, formatTime(task.getNextRunAt()));
        return task;
    }

    /**
     * 查询指定 robot 的所有任务。
     */
    public List<ScheduledTask> listTasks(String robotName) {
        return listTasks(ScheduleOwnerKey.main(robotName));
    }

    public List<ScheduledTask> listTasks(ScheduleOwnerKey owner) {
        String robotName = registerOwner(owner);
        List<ScheduledTask> tasks = tasksByRobot.get(robotName);
        if (tasks == null) return new ArrayList<>();
        synchronized (tasks) {
            return new ArrayList<>(tasks);
        }
    }

    /**
     * 取消任务。
     *
     * @return 被取消的任务，不存在则返回 null
     */
    public ScheduledTask cancelTask(String robotName, String taskId) {
        return cancelTask(ScheduleOwnerKey.main(robotName), taskId);
    }

    public ScheduledTask cancelTask(ScheduleOwnerKey owner, String taskId) {
        String robotName = registerOwner(owner);
        List<ScheduledTask> tasks = tasksByRobot.get(robotName);
        if (tasks == null) return null;

        ScheduledTask removed = null;
        synchronized (tasks) {
            for (int i = 0; i < tasks.size(); i++) {
                if (tasks.get(i).getId().equals(taskId)) {
                    removed = tasks.remove(i);
                    break;
                }
            }
        }
        if (removed != null) {
            deferredSchedules.remove(deferredKey(robotName, taskId));
            persistTasks(robotName);
            logger.info("定时任务已取消, robot={}, id={}, title={}",
                    robotName, taskId, removed.getTitle());
        }
        return removed;
    }

    /**
     * 更新任务（只更新非 null 的字段）。
     *
     * @return 更新后的任务，不存在则返回 null
     */
    public ScheduledTask updateTask(String robotName, String taskId,
                                    String newTitle, String newPrompt, ScheduleConfig newSchedule) {
        return updateTask(ScheduleOwnerKey.main(robotName), taskId,
                newTitle, newPrompt, newSchedule);
    }

    public ScheduledTask updateTask(ScheduleOwnerKey owner, String taskId,
                                    String newTitle, String newPrompt,
                                    ScheduleConfig newSchedule) {
        ensureOwnerWritable(owner);
        String robotName = registerOwner(owner);
        List<ScheduledTask> tasks = tasksByRobot.get(robotName);
        if (tasks == null) return null;

        ScheduledTask target = null;
        synchronized (tasks) {
            for (ScheduledTask t : tasks) {
                if (t.getId().equals(taskId) && ScheduledTask.STATUS_WAITING.equals(t.getStatus())) {
                    target = t;
                    break;
                }
            }
            if (target == null) return null;

            if (newTitle != null && !newTitle.isEmpty()) {
                target.setTitle(newTitle);
            }
            if (newPrompt != null && !newPrompt.isEmpty()) {
                target.setPrompt(newPrompt);
            }
            if (newSchedule != null) {
                target.setSchedule(newSchedule);
                target.setNextRunAt(calculateNextRunAt(newSchedule));
            }
        }
        deferredSchedules.remove(deferredKey(robotName, taskId));
        persistTasks(robotName);
        logger.info("定时任务已更新, robot={}, id={}", robotName, taskId);
        return target;
    }

    public String findGroupSession(ScheduleOwnerKey owner, String groupName) {
        String normalized = normalizeGroupName(groupName);
        if (normalized == null) return null;
        String storageId = registerOwner(owner);
        Map<String, String> sessions = groupSessionsByOwner.get(storageId);
        return sessions == null ? null : sessions.get(normalized);
    }

    public void bindGroupSession(ScheduleOwnerKey owner, String groupName,
                                 String sessionId) {
        String normalized = normalizeGroupName(groupName);
        if (normalized == null) {
            throw new IllegalArgumentException("groupName must not be blank");
        }
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        ensureOwnerWritable(owner);
        String storageId = registerOwner(owner);
        groupSessionsByOwner.computeIfAbsent(
                storageId, ignored -> new ConcurrentHashMap<>())
                .put(normalized, sessionId.trim());
        persistGroupSessions(storageId);
        logger.info("定时会话分组已绑定, owner={}, groupName={}, sessionId={}",
                owner, normalized, sessionId);
    }

    // ==================== 调度逻辑 ====================

    private void startScheduler() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "schedule-task-scanner");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::scan, 30, 60, TimeUnit.SECONDS);
    }

    /**
     * 每分钟扫描一次，触发到期任务。
     */
    private void scan() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, List<ScheduledTask>> entry : tasksByRobot.entrySet()) {
            String robotName = entry.getKey();
            List<ScheduledTask> tasks = entry.getValue();

            // 同 robot 串行：如果有 RUNNING 任务，跳过
            if (Boolean.TRUE.equals(runningByRobot.get(robotName))) {
                continue;
            }

            ScheduledTask toRun = null;
            synchronized (tasks) {
                for (ScheduledTask task : tasks) {
                    if (ScheduledTask.STATUS_WAITING.equals(task.getStatus())
                            && task.getNextRunAt() <= now) {
                        toRun = task;
                        break;
                    }
                }
                if (toRun != null) {
                    toRun.setStatus(ScheduledTask.STATUS_RUNNING);
                }
            }

            if (toRun != null) {
                runningByRobot.put(robotName, true);
                persistTasks(robotName);
                triggerExecution(robotName, toRun);
            }
        }
    }

    /**
     * 触发任务执行。
     */
    private void triggerExecution(String robotName, ScheduledTask task) {
        logger.info("触发定时任务执行, robot={}, id={}, title={}", robotName, task.getId(), task.getTitle());

        ScheduleOwnerKey owner = ownerFor(robotName);
        long scheduledAt = task.getNextRunAt();
        String executionId = beginExecutionRecord(
                owner, task, nextExecutionAttempt(robotName, task.getId()));

        if (executionCallback == null && scopedExecutionCallback == null) {
            logger.error("executionCallback 未设置，无法执行定时任务");
            onTaskCompleted(robotName, task.getId(), false);
            finishExecutionRecord(executionId, "FAILED",
                    "CALLBACK_NOT_CONFIGURED", "execution callback is not configured");
            return;
        }

        // 异步执行，不阻塞扫描线程
        Thread execThread = new Thread(() -> {
            try {
                String prompt = String.format("[定时任务触发] 任务: %s\n\n%s",
                        task.getTitle(), task.getPrompt());
                if (task.getChannelDeliveryContext() != null) {
                    prompt += "\n\n[定时任务投递]\n"
                            + "本任务已绑定创建时的原始外部信道会话。请直接生成应送达用户的最终内容；"
                            + "如需调用 talk_to，target 必须精确设置为“回复”。"
                            + "不要查询历史记录、猜测 userid 或改用默认信道目标。";
                }
                String effectiveGroupName = resolveGroupName(
                        task.getGroupName(), scheduledAt, ZoneId.systemDefault());
                boolean triggered = scopedExecutionCallback != null
                        ? scopedExecutionCallback.execute(owner, task.getId(),
                                effectiveGroupName, prompt, task.getAuthPrincipalContext(),
                                task.getChannelDeliveryContext())
                        : executionCallback.execute(robotName, task.getId(), prompt);
                if (!triggered) {
                    // 目标未通过执行门禁，回退状态，下一轮重试。具体原因由执行端记录。
                    logger.info("定时任务未执行（目标暂不可执行），等待下一轮, robot={}, id={}",
                            robotName, task.getId());
                    recordDeferred(robotName, task);
                    synchronized (tasksByRobot.get(robotName)) {
                        task.setStatus(ScheduledTask.STATUS_WAITING);
                    }
                    runningByRobot.put(robotName, false);
                    persistTasks(robotName);
                    finishExecutionRecord(executionId, "DEFERRED",
                            "TARGET_NOT_EXECUTABLE",
                            "execution admission returned false; retry scheduled");
                    return;
                }
                DeferredScheduleState deferred = deferredSchedules.remove(
                        deferredKey(robotName, task.getId()));
                if (deferred != null) {
                    logger.info("定时任务延迟恢复执行, robot={}, id={}, scheduledAt={},"
                                    + " delayMillis={}, deferredMillis={}, deferredAttempts={}",
                            robotName, task.getId(), formatTime(task.getNextRunAt()),
                            System.currentTimeMillis() - task.getNextRunAt(),
                            System.currentTimeMillis() - deferred.firstDeferredAt,
                            deferred.attempts);
                }
                onTaskCompleted(robotName, task.getId(), true);
                finishExecutionRecord(executionId, "SUCCESS",
                        "EXECUTION_ACCEPTED", null);
            } catch (Exception e) {
                logger.error("定时任务执行异常, robot={}, id={}", robotName, task.getId(), e);
                onTaskCompleted(robotName, task.getId(), false);
                finishExecutionRecord(executionId, "FAILED",
                        "EXECUTION_EXCEPTION", e.getClass().getName() + ": " + e.getMessage());
            }
        }, "schedule-exec-" + robotName);
        execThread.setDaemon(true);
        execThread.start();
    }

    /**
     * 任务执行完成回调（由执行线程调用）。
     *
     * @param success true=正常完成，false=FAILED
     */
    public void onTaskCompleted(String robotName, String taskId, boolean success) {
        runningByRobot.put(robotName, false);
        deferredSchedules.remove(deferredKey(robotName, taskId));

        List<ScheduledTask> tasks = tasksByRobot.get(robotName);
        if (tasks == null) return;

        synchronized (tasks) {
            ScheduledTask task = null;
            for (ScheduledTask t : tasks) {
                if (t.getId().equals(taskId)) {
                    task = t;
                    break;
             }
            }
            if (task == null) return;

            if (!success) {
                // FAILED: 直接删除
                tasks.remove(task);
                logger.info("定时任务执行失败，已删除, robot={}, id={}", robotName, taskId);
            } else if (task.getSchedule().isCron()) {
                // cron: 重算 nextRunAt，回到 WAITING
                task.setStatus(ScheduledTask.STATUS_WAITING);
                task.setLastRunAt(System.currentTimeMillis());
                task.setNextRunAt(calculateNextRunAt(task.getSchedule()));
                logger.info("cron 任务执行完成，下次执行: {}, robot={}, id={}",
            formatTime(task.getNextRunAt()), robotName, taskId);
            } else {
                // once: 删除
                tasks.remove(task);
                logger.info("once 任务执行完成，已删除, robot={}, id={}", robotName, taskId);
            }
        }
        persistTasks(robotName);
    }

    private void recordDeferred(String robotName, ScheduledTask task) {
        String key = deferredKey(robotName, task.getId());
        DeferredScheduleState state = deferredSchedules.computeIfAbsent(
                key, ignored -> new DeferredScheduleState(System.currentTimeMillis()));
        int attempts;
        synchronized (state) {
            state.attempts++;
            attempts = state.attempts;
        }
        if (attempts == 5 || attempts % 30 == 0) {
            logger.warn("定时任务持续延迟, robot={}, id={}, scheduledAt={},"
                            + " delayMillis={}, deferredAttempts={}",
                    robotName, task.getId(), formatTime(task.getNextRunAt()),
                    System.currentTimeMillis() - task.getNextRunAt(), attempts);
        }
    }

    private int nextExecutionAttempt(String robotName, String taskId) {
        DeferredScheduleState state = deferredSchedules.get(
                deferredKey(robotName, taskId));
        if (state == null) return 1;
        synchronized (state) {
            return state.attempts + 1;
        }
    }

    private String beginExecutionRecord(ScheduleOwnerKey owner,
                                        ScheduledTask task, int attempt) {
        try {
            return executionJournal.begin(
                    owner, task, attempt, System.currentTimeMillis());
        } catch (Exception error) {
            logger.error("定时任务执行记录写入失败, phase=begin, owner={}, taskId={}",
                    owner, task.getId(), error);
            return null;
        }
    }

    private void finishExecutionRecord(String executionId, String status,
                                       String resultCode, String detail) {
        if (executionId == null) return;
        try {
            executionJournal.finish(executionId, status, resultCode,
                    detail, System.currentTimeMillis());
        } catch (Exception error) {
            logger.error("定时任务执行记录写入失败, phase=finish, executionId={},"
                            + " status={}, resultCode={}",
                    executionId, status, resultCode, error);
        }
    }

    private static String deferredKey(String robotName, String taskId) {
        return robotName + "\n" + taskId;
    }

    private static final class DeferredScheduleState {
        private final long firstDeferredAt;
        private int attempts;

        private DeferredScheduleState(long firstDeferredAt) {
            this.firstDeferredAt = firstDeferredAt;
        }
    }

    // ==================== 持久化 ====================

    private void loadAllTasks() {
        Path baseDir = schedulesBaseDir;
        if (!Files.exists(baseDir)) return;

        try (java.util.stream.Stream<Path> paths = Files.walk(
                baseDir, MAX_SCHEDULE_FILE_DEPTH)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> TASKS_FILE.equals(path.getFileName().toString()))
                    .forEach(tasksFile -> loadTasksFile(baseDir, tasksFile));
        } catch (IOException e) {
            logger.error("扫描 schedules 目录失败", e);
        }
        loadAllGroupSessions();
    }

    private void loadAllGroupSessions() {
        if (!Files.exists(schedulesBaseDir)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(
                schedulesBaseDir, MAX_SCHEDULE_FILE_DEPTH)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> GROUP_SESSIONS_FILE.equals(
                            path.getFileName().toString()))
                    .forEach(this::loadGroupSessionsFile);
        } catch (IOException e) {
            logger.error("扫描定时会话分组失败", e);
        }
    }

    private void loadGroupSessionsFile(Path file) {
        String persistencePath = schedulesBaseDir.relativize(file.getParent())
                .toString().replace(java.io.File.separatorChar, '/');
        try {
            ScheduleOwnerKey owner =
                    ScheduleOwnerKey.fromPersistencePath(persistencePath);
            Map<String, String> sessions = GSON.fromJson(
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8),
                    GROUP_SESSION_MAP_TYPE);
            if (sessions == null || sessions.isEmpty()) return;
            String storageId = registerLoadedOwner(owner);
            Map<String, String> normalized = new ConcurrentHashMap<>();
            for (Map.Entry<String, String> entry : sessions.entrySet()) {
                String groupName = normalizeGroupName(entry.getKey());
                String sessionId = entry.getValue();
                if (groupName != null && sessionId != null
                        && !sessionId.trim().isEmpty()) {
                    normalized.put(groupName, sessionId.trim());
                }
            }
            if (!normalized.isEmpty()) {
                groupSessionsByOwner.put(storageId, normalized);
            }
        } catch (Exception e) {
            logger.error("读取定时会话分组失败, file={}", file, e);
        }
    }

    private void persistGroupSessions(String storageId) {
        ScheduleOwnerKey owner = ownerFor(storageId);
        Path dir = schedulesBaseDir.resolve(owner.getPersistencePath()).normalize();
        if (!dir.startsWith(schedulesBaseDir)) {
            throw new IllegalArgumentException(
                    "schedule persistence path escapes base directory");
        }
        Path file = dir.resolve(GROUP_SESSIONS_FILE);
        Map<String, String> sessions = groupSessionsByOwner.get(storageId);
        try {
            if (sessions == null || sessions.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            Files.createDirectories(dir);
            Files.write(file, GSON.toJson(sessions)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "持久化定时会话分组失败, owner=" + owner, e);
        }
    }

    private void persistTasks(String robotName) {
        List<ScheduledTask> tasks = tasksByRobot.get(robotName);
        ScheduleOwnerKey owner = ownerFor(robotName);
        Path base = schedulesBaseDir;
        Path dir = base.resolve(owner.getPersistencePath()).normalize();
        if (!dir.startsWith(base)) {
            throw new IllegalArgumentException("schedule persistence path escapes base directory");
        }
        Path file = dir.resolve(TASKS_FILE);

        try {
            if (tasks == null || tasks.isEmpty()) {
                // 无任务时删除文件
                Files.deleteIfExists(file);
                tasksByRobot.remove(robotName);
                return;
            }

            Files.createDirectories(dir);
            String json;
            synchronized (tasks) {
                json = GSON.toJson(tasks);
            }
            Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.error("持久化 tasks.json 失败, robot={}", robotName, e);
        }
    }

    private void loadTasksFile(Path baseDir, Path tasksFile) {
        String persistencePath = baseDir.relativize(tasksFile.getParent())
                .toString().replace(java.io.File.separatorChar, '/');
        try {
            ScheduleOwnerKey directoryOwner =
                    ScheduleOwnerKey.fromPersistencePath(persistencePath);
            String json = new String(
                    Files.readAllBytes(tasksFile), StandardCharsets.UTF_8);
            List<ScheduledTask> tasks = GSON.fromJson(json, TASK_LIST_TYPE);
            if (tasks == null || tasks.isEmpty()) {
                return;
            }
            ScheduleOwnerKey loadedOwner = directoryOwner;
            for (ScheduledTask task : tasks) {
                ScheduleOwnerKey taskOwner = task.getOwner();
                if (taskOwner != null
                        && persistencePath.equals(taskOwner.getPersistencePath())) {
                    if (loadedOwner == directoryOwner) {
                        loadedOwner = taskOwner;
                    } else if (!sameOwnerIdentity(loadedOwner, taskOwner)) {
                        logger.warn("同一 tasks.json 包含不一致的 owner，统一使用首个身份,"
                                        + " file={}, selectedOwner={}, ignoredOwner={}",
                                tasksFile, loadedOwner, taskOwner);
                    }
                }
            }
            String storageId = registerLoadedOwner(loadedOwner);
            List<ScheduledTask> loadedTasks = new ArrayList<>(tasks);
            tasksByRobot.put(storageId, loadedTasks);
            ScheduleOwnerKey canonicalOwner = ownerFor(storageId);
            boolean corrected = false;
            synchronized (loadedTasks) {
                for (ScheduledTask task : loadedTasks) {
                    if (!sameOwnerIdentity(task.getOwner(), canonicalOwner)) {
                        corrected = true;
                    }
                    task.setOwner(canonicalOwner);
                }
            }
            if (corrected) {
                persistTasks(storageId);
                logger.info("schedule task owner 已按当前身份迁移, storageId={}, ownerId={}",
                        storageId, canonicalOwner.getOwnerId());
            }
            logger.info("加载 schedule owner '{}' 的定时任务, 数量={}",
                    storageId, loadedTasks.size());
        } catch (Exception e) {
            logger.error("读取 tasks.json 失败, file={}", tasksFile, e);
        }
    }

    /**
     * 删除一个 owner 的任务、运行态和持久文件。Team delete 使用此入口；
     * 普通 shutdown 不调用，因而不会误删持久任务。
     */
    public void cleanupOwner(ScheduleOwnerKey owner) {
        String storageId = registerOwner(owner);
        tasksByRobot.remove(storageId);
        groupSessionsByOwner.remove(storageId);
        runningByRobot.remove(storageId);
        String deferredPrefix = storageId + "\n";
        deferredSchedules.keySet().removeIf(key -> key.startsWith(deferredPrefix));
        ownerKeys.remove(storageId);
        Path base = schedulesBaseDir;
        Path dir = base.resolve(owner.getPersistencePath()).normalize();
        if (!dir.startsWith(base)) {
            throw new IllegalArgumentException("schedule cleanup path escapes base directory");
        }
        try {
            Files.deleteIfExists(dir.resolve(TASKS_FILE));
            Files.deleteIfExists(dir.resolve(GROUP_SESSIONS_FILE));
            Files.deleteIfExists(dir);
            if (owner.isTeam()) {
                Path teamDir = dir.getParent();
                if (teamDir != null) Files.deleteIfExists(teamDir);
                Path teamRoot = teamDir == null ? null : teamDir.getParent();
                if (teamRoot != null && !teamRoot.equals(base)) {
                    Files.deleteIfExists(teamRoot);
                }
            }
        } catch (java.nio.file.DirectoryNotEmptyException ignored) {
            // 同 Team 的其他 member 或其他 Team 仍有任务，父目录应保留。
        } catch (IOException e) {
            logger.warn("清理 schedule owner 失败, owner={}", owner, e);
        }
    }

    public void cleanupTeam(String teamId) {
        blockedTeamIds.add(teamId);
        List<ScheduleOwnerKey> owners = new ArrayList<>(ownerKeys.values());
        for (ScheduleOwnerKey owner : owners) {
            if (owner.isTeam() && teamId.equals(owner.getTeamId())) {
                cleanupOwner(owner);
            }
        }
    }

    public boolean isTeamBlocked(String teamId) {
        return blockedTeamIds.contains(teamId);
    }

    public int teamOwnerCount() {
        int count = 0;
        for (ScheduleOwnerKey owner : ownerKeys.values()) {
            if (owner.isTeam()) count++;
        }
        return count;
    }

    public int teamOwnerCount(String teamId) {
        int count = 0;
        for (ScheduleOwnerKey owner : ownerKeys.values()) {
            if (owner.isTeam() && teamId.equals(owner.getTeamId())) count++;
        }
        return count;
    }

    public void cleanupOrphanTeams(Set<String> activeTeamIds) {
        Set<String> active = activeTeamIds == null
                ? java.util.Collections.emptySet() : activeTeamIds;
        List<ScheduleOwnerKey> owners = new ArrayList<>(ownerKeys.values());
        for (ScheduleOwnerKey owner : owners) {
            if (owner.isTeam() && !active.contains(owner.getTeamId())) {
                cleanupTeam(owner.getTeamId());
            }
        }
    }

    private void ensureOwnerWritable(ScheduleOwnerKey owner) {
        if (owner != null && owner.isTeam()
                && blockedTeamIds.contains(owner.getTeamId())) {
            throw new IllegalStateException(
                    "Team is DELETING; schedule writes are blocked");
        }
    }

    // ==================== MISSED 处理 ====================

    private void handleMissedTasks() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, List<ScheduledTask>> entry : tasksByRobot.entrySet()) {
            String robotName = entry.getKey();
            List<ScheduledTask> tasks = entry.getValue();
            boolean changed = false;

            synchronized (tasks) {
                List<ScheduledTask> toRemove = new ArrayList<>();
                for (ScheduledTask task : tasks) {
                    // 启动时发现 RUNNING 状态的任务（上次进程异常退出），视为 FAILED
                    if (ScheduledTask.STATUS_RUNNING.equals(task.getStatus())) {
                        if (task.getSchedule().isCron()) {
                            task.setStatus(ScheduledTask.STATUS_WAITING);
                            task.setNextRunAt(calculateNextRunAt(task.getSchedule()));
                            logger.info("MISSED(RUNNING) cron 任务重算, robot={}, id={}", robotName, task.getId());
                        } else {
                            toRemove.add(task);
                            logger.info("MISSED(RUNNING) once 任务删除, robot={}, id={}", robotName, task.getId());
                        }
                        changed = true;
                        continue;
                    }

                    // WAITING 但 nextRunAt < now（进程不在期间错过）
                    if (ScheduledTask.STATUS_WAITING.equals(task.getStatus())
                            && task.getNextRunAt() < now) {
                        if (task.getSchedule().isCron()) {
                            task.setNextRunAt(calculateNextRunAt(task.getSchedule()));
                            logger.info("MISSED cron 任务重算, robot={}, id={}, nextRunAt={}",
                                    robotName, task.getId(), formatTime(task.getNextRunAt()));
                        } else {
                            toRemove.add(task);
                            logger.info("MISSED once 任务删除, robot={}, id={}", robotName, task.getId());
                        }
                        changed = true;
                    }
                }
                tasks.removeAll(toRemove);
            }

            if (changed) {
                persistTasks(robotName);
            }
        }
    }

    // ==================== 工具方法 ====================

    public void register(ScheduleOwnerKey owner) {
        registerOwner(owner);
    }

    private String registerOwner(ScheduleOwnerKey owner) {
        if (owner == null) {
            throw new IllegalArgumentException("schedule owner must not be null");
        }
        String storageId = owner.getPersistencePath();
        ScheduleOwnerKey previous = ownerKeys.put(storageId, owner);
        if (previous != null && !sameOwnerIdentity(previous, owner)) {
            List<ScheduledTask> tasks = tasksByRobot.get(storageId);
            if (tasks != null) {
                synchronized (tasks) {
                    for (ScheduledTask task : tasks) {
                        task.setOwner(owner);
                    }
                }
                persistTasks(storageId);
            }
            logger.info("schedule owner 已用运行态身份修正, storageId={},"
                            + " previousOwnerId={}, ownerId={}",
                    storageId, previous.getOwnerId(), owner.getOwnerId());
        }
        return storageId;
    }

    /** Persistence-derived owners are fallback identities and must not replace live clients. */
    private String registerLoadedOwner(ScheduleOwnerKey owner) {
        if (owner == null) {
            throw new IllegalArgumentException("schedule owner must not be null");
        }
        String storageId = owner.getPersistencePath();
        ownerKeys.putIfAbsent(storageId, owner);
        return storageId;
    }

    private static boolean sameOwnerIdentity(ScheduleOwnerKey left,
                                             ScheduleOwnerKey right) {
        return left != null && right != null
                && java.util.Objects.equals(left.getOwnerId(), right.getOwnerId())
                && java.util.Objects.equals(left.getSurface(), right.getSurface())
                && java.util.Objects.equals(left.getLogicalId(), right.getLogicalId())
                && java.util.Objects.equals(left.getRobotName(), right.getRobotName())
                && java.util.Objects.equals(left.getTeamId(), right.getTeamId())
                && java.util.Objects.equals(left.getTeamMemberId(), right.getTeamMemberId())
                && java.util.Objects.equals(
                        left.getPersistencePath(), right.getPersistencePath());
    }

    private ScheduleOwnerKey ownerFor(String storageId) {
        ScheduleOwnerKey owner = ownerKeys.get(storageId);
        if (owner != null) {
            return owner;
        }
        owner = ScheduleOwnerKey.fromPersistencePath(storageId);
        ownerKeys.putIfAbsent(storageId, owner);
        return owner;
    }

    private static String normalizeGroupName(String groupName) {
        if (groupName == null) return null;
        String normalized = groupName.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("groupName length must be <= 100");
        }
        return normalized;
    }

    private static void validateGroupNameTemplate(String groupName) {
        if (groupName == null) return;
        resolveGroupName(groupName, 0L, ZoneId.systemDefault());
    }

    static String resolveGroupName(String groupName, long scheduledAt,
                                   ZoneId zoneId) {
        String normalized = normalizeGroupName(groupName);
        if (normalized == null) return null;
        if (zoneId == null) throw new IllegalArgumentException("zoneId must not be null");

        Matcher matcher = GROUP_DATE_TEMPLATE_PATTERN.matcher(normalized);
        StringBuffer resolved = new StringBuffer();
        int lastEnd = 0;
        while (matcher.find()) {
            String between = normalized.substring(lastEnd, matcher.start());
            if (between.indexOf('{') >= 0 || between.indexOf('}') >= 0) {
                throw new IllegalArgumentException("groupName contains malformed date template");
            }
            String pattern = matcher.group(1);
            if (!GROUP_DATE_FORMAT_PATTERN.matcher(pattern).matches()
                    || !pattern.matches(".*[yMdHms].*")) {
                throw new IllegalArgumentException(
                        "groupName date template contains unsupported pattern: " + pattern);
            }
            String value;
            try {
                value = Instant.ofEpochMilli(scheduledAt).atZone(zoneId)
                        .format(DateTimeFormatter.ofPattern(pattern));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(
                        "groupName date template is invalid: " + pattern, error);
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(value));
            lastEnd = matcher.end();
        }
        String tail = normalized.substring(lastEnd);
        if (tail.indexOf('{') >= 0 || tail.indexOf('}') >= 0) {
            throw new IllegalArgumentException("groupName contains malformed date template");
        }
        matcher.appendTail(resolved);
        return normalizeGroupName(resolved.toString());
    }

    /**
     * 生成任务 ID：t{时间戳秒数}_{title-slug}
     */
    private String generateId(String title) {
        long seconds = System.currentTimeMillis() / 1000;
        String slug = toSlug(title);
        return "t" + seconds + "_" + slug;
    }

    /**
     * 将标题转为 slug（小写，非字母数字替换为 -，去除首尾 -）。
     */
    private String toSlug(String title) {
        if (title == null || title.isEmpty()) return "task";
        // 保留字母、数字、中文，其他替换为 -
        String slug = title.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = "task";
        // 限制长度
        if (slug.length() > 30) slug = slug.substring(0, 30);
        return slug;
    }

    /**
     * 计算下次执行时间。
     */
    private long calculateNextRunAt(ScheduleConfig config) {
        if (config.isCron()) {
            return calculateCronNextRunAt(config.getExpr());
        } else {
            return calculateOnceRunAt(config.getExpr());
        }
    }

    private long calculateCronNextRunAt(String cronExpr) {
        try {
            Cron cron = cronParser.parse(cronExpr);
            ExecutionTime executionTime = ExecutionTime.forCron(cron);
            ZonedDateTime now = ZonedDateTime.now();
            Optional<ZonedDateTime> next = executionTime.nextExecution(now);
            return next.map(zdt -> zdt.toInstant().toEpochMilli())
                    .orElse(System.currentTimeMillis() + 86400000L); // 兜底：1天后
        } catch (Exception e) {
            logger.error("cron 表达式解析失败: {}", cronExpr, e);
            return System.currentTimeMillis() + 86400000L;
        }
    }

    private long calculateOnceRunAt(String expr) {
        // 相对时间：+30s, +30m, +2h, +1d
        Matcher matcher = RELATIVE_TIME_PATTERN.matcher(expr.trim());
        if (matcher.matches()) {
            long value = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2);
            long millis;
            switch (unit) {
                case "s": millis = value * 1000; break;
                case "m": millis = value * 60 * 1000; break;
                case "h": millis = value * 3600 * 1000; break;
                case "d": millis = value * 86400 * 1000; break;
                default: millis = value * 60 * 1000;
            }
            return System.currentTimeMillis() + millis;
        }

        // ISO 时间戳
        try {
            // 尝试带时区解析
            ZonedDateTime zdt = ZonedDateTime.parse(expr);
            return zdt.toInstant().toEpochMilli();
        } catch (DateTimeParseException e1) {
            try {
                // 尝试不带时区解析（使用系统默认时区）
                LocalDateTime ldt = LocalDateTime.parse(expr);
                return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (DateTimeParseException e2) {
                logger.error("无法解析 once 时间表达式: {}", expr);
                return System.currentTimeMillis() + 3600000L; // 兜底：1小时后
            }
        }
    }

    /**
     * 格式化时间戳为可读字符串。
     */
    public static String formatTime(long millis) {
        return Instant.ofEpochMilli(millis)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    /**
     * 格式化 list 操作的返回文本。
     */
    public String formatTaskList(String robotName) {
        return formatTaskList(ScheduleOwnerKey.main(robotName));
    }

    public String formatTaskList(ScheduleOwnerKey owner) {
        List<ScheduledTask> tasks = listTasks(owner);
        if (tasks.isEmpty()) {
            return "[定时任务列表]\n当前没有定时任务。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[定时任务列表]\n");
        sb.append("共 ").append(tasks.size()).append(" 个任务：\n\n");

        int idx = 1;
        for (ScheduledTask task : tasks) {
            sb.append(idx++).append(". ID: ").append(task.getId()).append("\n");
            sb.append("   标题: ").append(task.getTitle()).append("\n");
            sb.append("   内容: ").append(task.getPrompt()).append("\n");
            if (task.getGroupName() != null) {
                sb.append("   会话分组: ").append(task.getGroupName()).append("\n");
            }
            sb.append("   调度: ").append(task.getSchedule().getType())
                    .append(" ").append(task.getSchedule().getExpr()).append("\n");
            sb.append("   状态: ").append(task.getStatus()).append("\n");
            sb.append("   下次执行: ").append(formatTime(task.getNextRunAt())).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 格式化操作结果文本（cancel/update/create）。
     */
    public static String formatOperationResult(String operation, ScheduledTask task) {
        if (task == null) {
            return "[定时任务操作结果]\n操作: " + operation + "\n结果: 任务不存在或状态不允许操作";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[定时任务操作结果]\n");
        sb.append("操作: ").append(operation).append("\n");
        sb.append("任务ID: ").append(task.getId()).append("\n");
        sb.append("标题: ").append(task.getTitle()).append("\n");
        if (task.getGroupName() != null) {
            sb.append("会话分组: ").append(task.getGroupName()).append("\n");
        }
        if ("create".equals(operation)) {
            sb.append("调度: ").append(task.getSchedule().getType())
                    .append(" ").append(task.getSchedule().getExpr()).append("\n");
            sb.append("下次执行: ").append(formatTime(task.getNextRunAt())).append("\n");
        } else if ("update".equals(operation)) {
            sb.append("调度: ").append(task.getSchedule().getType())
                    .append(" ").append(task.getSchedule().getExpr()).append("\n");
            sb.append("下次执行: ").append(formatTime(task.getNextRunAt())).append("\n");
        } else if ("cancel".equals(operation)) {
            sb.append("结果: 已取消\n");
        }
        return sb.toString();
    }

    // ==================== 回调接口 ====================

    /**
     * 定时任务执行回调接口。
     * 由 AcpProxy 层实现，负责检查 client 状态、新建 session 并发送 prompt。
     */
    public interface ScheduleExecutionCallback {
        /**
         * 执行定时任务。
         * <p>
         * 实现方应检查对应 robot 的 client 是否空闲：
         * - 空闲：新建 session 并发送 prompt
         * - 忙碌：返回 false，调度器下一轮重试
         *
         * @param robotName robot 名称
         * @param taskId    任务 ID
         * @param prompt    要发送的 prompt（包含 [定时任务触发] 前缀）
         * @return true=已触发执行，false=client 忙碌未执行
         */
        boolean execute(String robotName, String taskId, String prompt);
    }

    @FunctionalInterface
    public interface ScopedScheduleExecutionCallback {
        boolean execute(ScheduleOwnerKey owner, String taskId,
                        String groupName, String prompt,
                        AuthPrincipalContext authPrincipalContext,
                        ChannelDeliveryContext channelDeliveryContext);
    }

    // ==================== MCP 工具处理 ====================

    public String executeTool(String toolName, JsonObject arguments,
                              ScheduleOwnerKey owner,
                              AuthPrincipalContext authPrincipalContext,
                              ChannelDeliveryContext channelDeliveryContext) {
        switch (toolName) {
            case "schedule_task":
                return handleCreate(arguments, owner, authPrincipalContext,
                        channelDeliveryContext);
            case "manage_schedule":
                return handleManage(arguments, owner);
            default:
                throw new IllegalArgumentException("Unsupported schedule MCP tool: "
                        + toolName);
        }
    }

    private String handleCreate(JsonObject json, ScheduleOwnerKey owner,
                                AuthPrincipalContext authPrincipalContext,
                                ChannelDeliveryContext channelDeliveryContext) {
        JsonArray tasks = json.getAsJsonArray("tasks");
        String groupName = json.has("groupName")
                ? json.get("groupName").getAsString() : null;
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < tasks.size(); i++) {
            JsonObject taskJson = tasks.get(i).getAsJsonObject();
            String title = taskJson.get("title").getAsString();
            String prompt = taskJson.get("prompt").getAsString();
            JsonObject scheduleJson = taskJson.getAsJsonObject("schedule");

            ScheduleConfig config = new ScheduleConfig(
                    scheduleJson.get("type").getAsString(),
                    scheduleJson.get("expr").getAsString());

            ScheduledTask created = createTask(
                    owner, title, prompt, config, groupName, authPrincipalContext,
                    channelDeliveryContext);
            result.append(formatOperationResult("create", created));
            if (i < tasks.size() - 1) result.append("\n");
        }
        return result.toString();
    }

    private String handleManage(JsonObject json, ScheduleOwnerKey owner) {
        String operation = json.get("operation").getAsString();

        switch (operation) {
            case "list":
                return formatTaskList(owner);

            case "cancel": {
                String taskId = json.get("taskId").getAsString();
                ScheduledTask cancelled = cancelTask(owner, taskId);
                return formatOperationResult("cancel", cancelled);
            }

            case "update": {
                String taskId = json.get("taskId").getAsString();
                JsonObject updates = json.getAsJsonObject("updates");

                String newTitle = updates != null && updates.has("title")
                        ? updates.get("title").getAsString() : null;
                String newPrompt = updates != null && updates.has("prompt")
                        ? updates.get("prompt").getAsString() : null;
                ScheduleConfig newSchedule = null;
                if (updates != null && updates.has("schedule")) {
                    JsonObject schedJson = updates.getAsJsonObject("schedule");
                    newSchedule = new ScheduleConfig(
                            schedJson.get("type").getAsString(),
                            schedJson.get("expr").getAsString());
                }

                ScheduledTask updated = updateTask(owner, taskId, newTitle, newPrompt, newSchedule);
                return formatOperationResult("update", updated);
            }

            default:
                return "[定时任务操作结果]\n未知操作: " + operation;
        }
    }

}
