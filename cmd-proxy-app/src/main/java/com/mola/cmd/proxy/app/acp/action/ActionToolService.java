package com.mola.cmd.proxy.app.acp.action;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Provider-neutral application service for cmd-proxy-owned MCP action tools. */
public final class ActionToolService {

    @FunctionalInterface
    public interface Handler {
        String execute(JsonObject arguments) throws Exception;
    }

    private final Map<String, Handler> handlers;

    public ActionToolService(Handler dispatchSubagent,
                             Handler scheduleTask,
                             Handler manageSchedule,
                             Handler talkTo) {
        Map<String, Handler> configured = new LinkedHashMap<>();
        configured.put("dispatch_subagent", dispatchSubagent);
        configured.put("schedule_task", scheduleTask);
        configured.put("manage_schedule", manageSchedule);
        configured.put("talk_to", talkTo);
        handlers = Collections.unmodifiableMap(configured);
    }

    public String execute(String toolName, JsonObject arguments) throws Exception {
        Handler handler = handlers.get(toolName);
        if (handler == null) {
            throw new IllegalArgumentException("Unknown cmd-proxy tool: " + toolName);
        }
        return handler.execute(arguments == null ? new JsonObject() : arguments);
    }
}
