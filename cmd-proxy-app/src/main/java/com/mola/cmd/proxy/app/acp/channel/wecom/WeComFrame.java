package com.mola.cmd.proxy.app.acp.channel.wecom;

import com.google.gson.JsonObject;

/** Minimal parsed WeCom WebSocket frame. */
public final class WeComFrame {
    private final String cmd;
    private final String requestId;
    private final Integer errorCode;
    private final String errorMessage;
    private final JsonObject body;

    WeComFrame(String cmd, String requestId, Integer errorCode,
               String errorMessage, JsonObject body) {
        this.cmd = cmd;
        this.requestId = requestId;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.body = body;
    }

    public String getCmd() { return cmd; }
    public String getRequestId() { return requestId; }
    public Integer getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public JsonObject getBody() { return body; }
    public boolean isSuccess() { return errorCode == null || errorCode == 0; }
}
