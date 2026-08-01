package com.mola.cmd.proxy.app.acp.team.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class TeamError {

    private TeamErrorCode code;
    private String message;
    private boolean retryable;
    private Map<String, String> details;
    private long timestamp;

    @SuppressWarnings("unused")
    private TeamError() {
    }

    public TeamError(TeamErrorCode code, String message, boolean retryable,
                     Map<String, String> details, long timestamp) {
        this.code = Objects.requireNonNull(code, "code");
        this.message = requireText(message, "message");
        this.retryable = retryable;
        this.details = details == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(details);
        this.timestamp = timestamp;
    }

    public static TeamError of(TeamErrorCode code, String message, boolean retryable) {
        return new TeamError(code, message, retryable, Collections.emptyMap(),
                System.currentTimeMillis());
    }

    public TeamErrorCode getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Map<String, String> getDetails() {
        return Collections.unmodifiableMap(details == null
                ? Collections.emptyMap() : details);
    }

    public long getTimestamp() {
        return timestamp;
    }

    static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
