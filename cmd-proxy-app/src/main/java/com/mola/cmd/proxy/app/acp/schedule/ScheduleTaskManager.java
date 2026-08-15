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
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleConfig;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduledTask;
import com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext;
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

    /** 相对时间表达式：+30s, +30m, +2h, +1d */
    private static final Pattern RELATIVE_TIME_PATTERN = Pattern.compile("^\\+(\\d+)([smhd])$");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TASK_LIST_TYPE = new TypeToken<List<ScheduledTask>>() {}.getType();
    private static final Type GROUP_SESSION_MAP_TYPE =
            new TypeToken<Map<String, String>>() {}.getType();

    private final Path schedulesBaseDir;

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
        ensureOwnerWritable(owner);
        String robotName = registerOwner(owner);
        ScheduledTask task = new ScheduledTask();
        task.setId(generateId(title));
        task.setOwner(owner);
        task.setTitle(title);
        task.setPrompt(prompt);
        task.setGroupName(normalizeGroupName(groupName));
        task.setSchedule(config);
        task.setStatus(ScheduledTask.STATUS_WAITING);
        task.setCreatedAt(System.currentTimeMillis());
        task.setNextRunAt(calculateNextRunAt(config));
        task.setAuthPrincipalContext(authPrincipalContext);

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

        if (executionCallback == null && scopedExecutionCallback == null) {
            logger.error("executionCallback 未设置，无法执行定时任务");
            onTaskCompleted(robotName, task.getId(), false);
            return;
        }

        // 异步执行，不阻塞扫描线程
        Thread execThread = new Thread(() -> {
            try {
                String prompt = String.format("[定时任务触发] 任务: %s\n\n%s",
                        task.getTitle(), task.getPrompt());
                ScheduleOwnerKey owner = ownerFor(robotName);
                boolean triggered = scopedExecutionCallback != null
                        ? scopedExecutionCallback.execute(owner, task.getId(),
                                task.getGroupName(), prompt, task.getAuthPrincipalContext())
                        : executionCallback.execute(robotName, task.getId(), prompt);
                if (!triggered) {
                    // client 忙碌，回退状态，下一轮重试
                    logger.info("定时任务未执行（client 忙碌），等待下一轮, robot={}, id={}",
                            robotName, task.getId());
                    synchronized (tasksByRobot.get(robotName)) {
                        task.setStatus(ScheduledTask.STATUS_WAITING);
                    }
                    runningByRobot.put(robotName, false);
                    persistTasks(robotName);
                    return;
                }
                onTaskCompleted(robotName, task.getId(), true);
            } catch (Exception e) {
                logger.error("定时任务执行异常, robot={}, id={}", robotName, task.getId(), e);
                onTaskCompleted(robotName, task.getId(), false);
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

    // ==================== 持久化 ====================

    private void loadAllTasks() {
        Path baseDir = schedulesBaseDir;
        if (!Files.exists(baseDir)) return;

        try (java.util.stream.Stream<Path> paths = Files.walk(baseDir, 4)) {
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
        try (java.util.stream.Stream<Path> paths = Files.walk(schedulesBaseDir, 4)) {
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
            String storageId = registerOwner(owner);
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
            ScheduleOwnerKey owner = directoryOwner;
            for (ScheduledTask task : tasks) {
                if (task.getOwner() == null) {
                    task.setOwner(directoryOwner);
                } else {
                    owner = task.getOwner();
                }
            }
            String storageId = registerOwner(owner);
            tasksByRobot.put(storageId, new ArrayList<>(tasks));
            logger.info("加载 schedule owner '{}' 的定时任务, 数量={}",
                    storageId, tasks.size());
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
        ownerKeys.put(storageId, owner);
        return storageId;
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
                        AuthPrincipalContext authPrincipalContext);
    }

    // ==================== JSON 指令处理（供 AcpClient 调用） ====================

    /**
     * 从 Agent 完整回复中检测并处理定时任务指令。
     * <p>
     * 合并了 JSON 提取和指令执行：先从 fullResponse 中检测 schedule_task / manage_schedule
     * 关键词并提取完整 JSON，再路由到对应操作。
     *
     * @param fullResponse Agent 的完整回复文本
     * @param robotName    当前 robot 名称
     * @return 操作结果文本（供注入回 Agent），未检测到指令时返回 null
     */
    public String detectAndHandle(String fullResponse, String robotName) {
        return detectAndHandle(fullResponse, ScheduleOwnerKey.main(robotName));
    }

    public String detectAndHandle(String fullResponse, ScheduleOwnerKey owner) {
        return detectAndHandle(fullResponse, owner, null);
    }

    public String detectAndHandle(String fullResponse, ScheduleOwnerKey owner,
                                  AuthPrincipalContext authPrincipalContext) {
        String actionJson = extractActionJson(fullResponse);
        if (actionJson == null) return null;
        return handleAction(actionJson, owner, authPrincipalContext);
    }

    /**
     * 处理 Agent 输出的定时任务 JSON 指令。
     * <p>
     * 解析 action 字段，路由到对应的操作（create/list/cancel/update），
     * 返回格式化的结果文本供注入回 Agent。
     *
     * @param actionJson 完整的 JSON 字符串
     * @param robotName  当前 robot 名称
     * @return 操作结果文本，action 不匹配时返回 null
     */
    public String handleAction(String actionJson, String robotName) {
        return handleAction(actionJson, ScheduleOwnerKey.main(robotName));
    }

    public String handleAction(String actionJson, ScheduleOwnerKey owner) {
        return handleAction(actionJson, owner, null);
    }

    public String handleAction(String actionJson, ScheduleOwnerKey owner,
                               AuthPrincipalContext authPrincipalContext) {
        JsonObject json = JsonParser.parseString(actionJson).getAsJsonObject();
        String action = json.get("action").getAsString();

        switch (action) {
            case "schedule_task":
                return handleCreate(json, owner, authPrincipalContext);
            case "manage_schedule":
                return handleManage(json, owner);
            default:
                return null;
        }
    }

    private String handleCreate(JsonObject json, ScheduleOwnerKey owner,
                                AuthPrincipalContext authPrincipalContext) {
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
                    owner, title, prompt, config, groupName, authPrincipalContext);
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

    // ==================== JSON 提取 ====================

    /**
     * 从 Agent 完整回复中提取 schedule_task 或 manage_schedule JSON。
     *
     * @return 第一个匹配的完整 JSON 字符串，不存在则返回 null
     */
    private String extractActionJson(String fullResponse) {
        int scheduleIdx = fullResponse.indexOf("schedule_task");
        int manageIdx = fullResponse.indexOf("manage_schedule");

        int targetIdx = -1;
        if (scheduleIdx >= 0 && manageIdx >= 0) {
            targetIdx = Math.min(scheduleIdx, manageIdx);
        } else if (scheduleIdx >= 0) {
            targetIdx = scheduleIdx;
        } else if (manageIdx >= 0) {
            targetIdx = manageIdx;
        }

        if (targetIdx < 0) return null;

        int braceStart = fullResponse.lastIndexOf('{', targetIdx);
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
}
