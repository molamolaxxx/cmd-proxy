package com.mola.cmd.proxy.app.acp.mcpauth;

import java.util.Objects;

/** Channel-neutral authenticated principal propagated across derived Agent work. */
public final class AuthPrincipalContext {
    private String principalId;
    private String displayName;
    private String sourceType;
    private String sourceId;

    private AuthPrincipalContext() { }

    public AuthPrincipalContext(String principalId, String displayName,
                                String sourceType, String sourceId) {
        this.principalId = requireText(principalId, "principalId");
        this.displayName = clean(displayName);
        this.sourceType = requireText(sourceType, "sourceType");
        this.sourceId = clean(sourceId);
    }

    public String getPrincipalId() { return principalId; }
    public String getDisplayName() { return displayName; }
    public String getSourceType() { return sourceType; }
    public String getSourceId() { return sourceId; }

    private static String requireText(String value, String field) {
        String clean = clean(Objects.requireNonNull(value, field));
        if (clean.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return clean;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
