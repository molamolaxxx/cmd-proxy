package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONObject;

/** Projects one eventId into live/history UI; implementations must deduplicate by eventId. */
public interface TaskCardProjector {
    void project(JSONObject assignee, JSONObject card);
}
