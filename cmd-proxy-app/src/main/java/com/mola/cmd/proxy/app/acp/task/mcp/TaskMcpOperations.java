package com.mola.cmd.proxy.app.acp.task.mcp;

import com.alibaba.fastjson.JSONObject;

/** The three business operations exposed by the independent task MCP transport. */
public interface TaskMcpOperations {
    JSONObject getTask(String taskId, JSONObject query);

    JSONObject updateTaskStatus(String taskId, JSONObject request);

    JSONObject addTaskComment(String taskId, JSONObject request);
}
