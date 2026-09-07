package com.mola.cmd.proxy.app.acp.task.mcp;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.task.service.TaskService;

import java.util.Objects;

/** Thin MCP adapter; all validation, state transitions and idempotency remain in TaskService. */
public final class TaskServiceMcpOperations implements TaskMcpOperations {
    private final TaskService service;

    public TaskServiceMcpOperations(TaskService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public JSONObject getTask(String taskId, JSONObject query) {
        return service.get(taskId, query);
    }

    @Override
    public JSONObject updateTaskStatus(String taskId, JSONObject request) {
        request.put("reopen", false);
        request.put("actorType", "AGENT");
        return service.updateStatus(taskId, request);
    }

    @Override
    public JSONObject addTaskComment(String taskId, JSONObject request) {
        request.put("authorType", "AGENT");
        return service.addComment(taskId, request);
    }
}
