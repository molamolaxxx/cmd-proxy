package com.mola.cmd.proxy.app.acp.schedule;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleConfig;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleExecutionRecord;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduledTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Reload-safe local bridge used by ConfigUI's scheduled-task administration API. */
public final class ScheduleAdminApiBridge {
    private static volatile ScheduleTaskManager manager;

    private ScheduleAdminApiBridge() {}

    public static void install(ScheduleTaskManager value) {
        manager = java.util.Objects.requireNonNull(value, "manager");
    }

    public static void clear(ScheduleTaskManager value) {
        if (manager == value) manager = null;
    }

    public static JSONObject listTasks(int page, int pageSize, String query,
                                       String scope, String type, String status) {
        List<ScheduledTask> filtered = new ArrayList<>();
        String needle = normalize(query);
        for (ScheduledTask task : current().listAllTaskSnapshots()) {
            ScheduleOwnerKey owner = task.getOwner();
            if (!matches(scope, owner == null ? null : owner.getScope().name())) continue;
            if (!matches(type, task.getSchedule() == null ? null
                    : task.getSchedule().getType())) continue;
            if (!matches(status, task.getStatus())) continue;
            if (needle != null && !searchText(task).contains(needle)) continue;
            filtered.add(task);
        }
        filtered.sort(Comparator.comparingLong(ScheduledTask::getNextRunAt)
                .thenComparing(ScheduledTask::getId));
        return pageTasks(filtered, page, pageSize);
    }

    public static JSONObject stats() {
        List<ScheduledTask> tasks = current().listAllTaskSnapshots();
        int cron = 0, once = 0, waiting = 0, running = 0;
        for (ScheduledTask task : tasks) {
            if (task.getSchedule() != null && task.getSchedule().isCron()) cron++;
            if (task.getSchedule() != null && task.getSchedule().isOnce()) once++;
            if (ScheduledTask.STATUS_WAITING.equals(task.getStatus())) waiting++;
            if (ScheduledTask.STATUS_RUNNING.equals(task.getStatus())) running++;
        }
        JSONObject result = new JSONObject(true);
        result.put("total", tasks.size());
        result.put("cron", cron);
        result.put("once", once);
        result.put("waiting", waiting);
        result.put("running", running);
        return result;
    }

    public static JSONObject update(String ownerPath, String taskId, JSONObject body) {
        requireText(ownerPath, "ownerPath");
        requireText(taskId, "taskId");
        String title = body.containsKey("title") ? requireText(body.getString("title"), "title") : null;
        String prompt = body.containsKey("prompt") ? requireText(body.getString("prompt"), "prompt") : null;
        ScheduleConfig schedule = null;
        if (body.containsKey("schedule")) {
            JSONObject value = body.getJSONObject("schedule");
            if (value == null) throw badRequest("schedule must be an object");
            schedule = new ScheduleConfig(requireText(value.getString("type"), "schedule.type"),
                    requireText(value.getString("expr"), "schedule.expr"));
        }
        boolean updateGroup = body.containsKey("groupName");
        ScheduledTask task;
        try {
            task = current().updateTaskByOwnerPath(ownerPath, taskId, title, prompt,
                    schedule, updateGroup, body.getString("groupName"));
        } catch (IllegalArgumentException error) {
            throw badRequest(error.getMessage());
        }
        if (task == null) throw new ApiException(409, "TASK_NOT_EDITABLE",
                "任务不存在或当前正在调度，请刷新后重试");
        return taskJson(task, true);
    }

    public static JSONObject delete(String ownerPath, String taskId) {
        ScheduledTask currentTask = current().findTaskSnapshot(ownerPath, taskId);
        if (currentTask == null) throw new ApiException(404, "TASK_NOT_FOUND", "定时任务不存在");
        if (ScheduledTask.STATUS_RUNNING.equals(currentTask.getStatus())) {
            throw new ApiException(409, "TASK_RUNNING", "任务正在调度，请稍后刷新后重试");
        }
        ScheduledTask removed = current().cancelTaskByOwnerPath(ownerPath, taskId);
        if (removed == null) throw new ApiException(404, "TASK_NOT_FOUND", "定时任务不存在");
        JSONObject result = new JSONObject(true);
        result.put("deleted", true);
        result.put("task", taskJson(removed, false));
        return result;
    }

    public static JSONObject executions(int page, int pageSize, String ownerPath,
                                        String taskId, String status) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        ScheduleTaskManager value = current();
        long total = value.countExecutionRecords(ownerPath, taskId, status);
        List<ScheduleExecutionRecord> records = value.listExecutionRecords(
                ownerPath, taskId, status, (safePage - 1) * safeSize, safeSize);
        JSONArray items = new JSONArray();
        for (ScheduleExecutionRecord record : records) items.add(executionJson(record));
        return page(items, safePage, safeSize, total);
    }

    private static JSONObject pageTasks(List<ScheduledTask> tasks, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        int from = Math.min(tasks.size(), (safePage - 1) * safeSize);
        int to = Math.min(tasks.size(), from + safeSize);
        JSONArray items = new JSONArray();
        for (ScheduledTask task : tasks.subList(from, to)) items.add(taskJson(task, true));
        return page(items, safePage, safeSize, tasks.size());
    }

    private static JSONObject page(JSONArray items, int page, int pageSize, long total) {
        JSONObject result = new JSONObject(true);
        result.put("items", items);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("total", total);
        result.put("totalPages", Math.max(1L, (total + pageSize - 1L) / pageSize));
        return result;
    }

    private static JSONObject taskJson(ScheduledTask task, boolean includePrompt) {
        JSONObject result = new JSONObject(true);
        result.put("taskId", task.getId());
        result.put("title", task.getTitle());
        if (includePrompt) result.put("prompt", task.getPrompt());
        result.put("groupName", task.getGroupName());
        result.put("status", task.getStatus());
        result.put("createdAt", task.getCreatedAt());
        result.put("lastRunAt", task.getLastRunAt());
        result.put("nextRunAt", task.getNextRunAt());
        ScheduleConfig schedule = task.getSchedule();
        JSONObject scheduleJson = new JSONObject(true);
        if (schedule != null) {
            scheduleJson.put("type", schedule.getType());
            scheduleJson.put("expr", schedule.getExpr());
        }
        result.put("schedule", scheduleJson);
        ScheduleOwnerKey owner = task.getOwner();
        JSONObject ownerJson = new JSONObject(true);
        if (owner != null) {
            ownerJson.put("scope", owner.getScope().name());
            ownerJson.put("ownerPath", owner.getPersistencePath());
            ownerJson.put("ownerId", owner.getOwnerId());
            ownerJson.put("surface", owner.getSurface() == null ? null : owner.getSurface().name());
            ownerJson.put("logicalId", owner.getLogicalId());
            ownerJson.put("robotName", owner.getRobotName());
            ownerJson.put("teamId", owner.getTeamId());
            ownerJson.put("teamMemberId", owner.getTeamMemberId());
        }
        result.put("owner", ownerJson);
        return result;
    }

    private static JSONObject executionJson(ScheduleExecutionRecord record) {
        JSONObject result = new JSONObject(true);
        result.put("executionId", record.getId());
        result.put("taskId", record.getTaskId());
        result.put("title", record.getTitle());
        result.put("ownerScope", record.getOwnerScope());
        result.put("ownerPath", record.getOwnerPath());
        result.put("ownerId", record.getOwnerId());
        result.put("robotName", record.getRobotName());
        result.put("teamId", record.getTeamId());
        result.put("teamMemberId", record.getTeamMemberId());
        result.put("groupName", record.getGroupName());
        result.put("scheduleType", record.getScheduleType());
        result.put("scheduleExpr", record.getScheduleExpr());
        result.put("scheduledAt", record.getScheduledAt());
        result.put("startedAt", record.getStartedAt());
        result.put("finishedAt", record.getFinishedAt());
        result.put("delayMillis", record.getDelayMillis());
        result.put("attempt", record.getAttempt());
        result.put("status", record.getStatus());
        result.put("resultCode", record.getResultCode());
        result.put("detail", record.getDetail());
        return result;
    }

    private static boolean matches(String filter, String value) {
        return normalize(filter) == null || normalize(filter).equals(normalize(value));
    }

    private static String searchText(ScheduledTask task) {
        ScheduleOwnerKey owner = task.getOwner();
        return ((task.getId() == null ? "" : task.getId()) + " "
                + (task.getTitle() == null ? "" : task.getTitle()) + " "
                + (owner == null ? "" : owner.getOwnerId()) + " "
                + (owner == null ? "" : owner.getRobotName()) + " "
                + (owner == null ? "" : owner.getTeamId()) + " "
                + (owner == null ? "" : owner.getTeamMemberId())).toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        if (value == null || value.trim().isEmpty() || "all".equalsIgnoreCase(value.trim())) return null;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw badRequest(field + " 不能为空");
        return value.trim();
    }

    private static ApiException badRequest(String message) {
        return new ApiException(400, "INVALID_ARGUMENT", message);
    }

    private static ScheduleTaskManager current() {
        ScheduleTaskManager value = manager;
        if (value == null) throw new ApiException(503, "SCHEDULE_NOT_READY", "定时任务服务尚未就绪");
        return value;
    }

    public static final class ApiException extends RuntimeException {
        private final int status;
        private final String code;
        public ApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
        public int getStatus() { return status; }
        public String getCode() { return code; }
    }
}
