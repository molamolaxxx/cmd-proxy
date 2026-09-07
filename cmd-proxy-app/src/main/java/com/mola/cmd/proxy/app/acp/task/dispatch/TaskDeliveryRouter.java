package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONObject;

/** Chooses the local MAIN, local Team or trusted mixed-Team transport for an assignee. */
public interface TaskDeliveryRouter {
    TaskDeliveryAdapter route(JSONObject assignee);
}
