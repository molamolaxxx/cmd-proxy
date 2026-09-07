package com.mola.cmd.proxy.app.acp.task.model;

import com.alibaba.fastjson.JSONObject;

/** Stable business failure shared by REST and MCP. */
public final class TaskException extends RuntimeException {
    private final String code;
    private final int httpStatus;
    private final JSONObject data;
    public TaskException(String code, String message, int httpStatus) { this(code, message, httpStatus, new JSONObject()); }
    public TaskException(String code, String message, int httpStatus, JSONObject data) {
        super(message); this.code = code; this.httpStatus = httpStatus; this.data = data;
    }
    public String getCode() { return code; }
    public int getHttpStatus() { return httpStatus; }
    public JSONObject getData() { return data; }
}
