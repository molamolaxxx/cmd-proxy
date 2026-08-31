package com.mola.cmd.proxy.app.acp.filepreview;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TextFilePreviewResult {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final String requestId;
    private final boolean accepted;
    private final String code;
    private final String message;
    private final boolean retryable;
    private final Map<String, Object> data;

    private TextFilePreviewResult(String requestId, boolean accepted, String code,
                                  String message, boolean retryable, Map<String, Object> data) {
        this.requestId = requestId == null ? "" : requestId;
        this.accepted = accepted;
        this.code = code;
        this.message = message;
        this.retryable = retryable;
        this.data = data;
    }

    public static TextFilePreviewResult success(String requestId, Map<String, Object> data) {
        return new TextFilePreviewResult(requestId, true, "OK", "success", false, data);
    }

    public static TextFilePreviewResult error(String requestId, String code,
                                              String message, boolean retryable) {
        return new TextFilePreviewResult(requestId, false, code, message, retryable, null);
    }

    public Map<String, String> toResultMap() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("schemaVersion", "1");
        result.put("requestId", requestId);
        result.put("accepted", Boolean.toString(accepted));
        result.put("code", code);
        result.put("message", message == null ? "" : message);
        result.put("retryable", Boolean.toString(retryable));
        if (data != null) result.put("data", GSON.toJson(data));
        return result;
    }

    public boolean isAccepted() { return accepted; }
    public String getCode() { return code; }
}
