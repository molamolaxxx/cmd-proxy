package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONObject;

/** Starts or queues the Agent turn after durable acceptance; submit must return promptly. */
public interface TaskPromptSink {
    boolean submit(JSONObject assignee, JSONObject card, String agentPrompt);
}
