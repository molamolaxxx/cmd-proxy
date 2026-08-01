package com.mola.cmd.proxy.app.acp.team;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;

/**
 * 来源 robot 配置的内存快照。完整配置只在内存中深拷贝，持久层仅保存 fingerprint
 * 和非敏感身份/展示字段。
 */
public final class TeamSourceRobotSnapshot {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final String sourceGroupId;
    private final String sourceRobotId;
    private final String configFingerprint;
    private final long capturedAt;
    private final AcpRobotParam robotParam;

    public TeamSourceRobotSnapshot(String sourceGroupId, String sourceRobotId,
                                   String configFingerprint, long capturedAt,
                                   AcpRobotParam robotParam) {
        this.sourceGroupId = requireText(sourceGroupId, "sourceGroupId");
        this.sourceRobotId = requireText(sourceRobotId, "sourceRobotId");
        this.configFingerprint = requireText(configFingerprint, "configFingerprint");
        this.capturedAt = capturedAt;
        this.robotParam = deepCopy(robotParam);
    }

    public String getSourceGroupId() {
        return sourceGroupId;
    }

    public String getSourceRobotId() {
        return sourceRobotId;
    }

    public String getConfigFingerprint() {
        return configFingerprint;
    }

    public long getCapturedAt() {
        return capturedAt;
    }

    public AcpRobotParam copyRobotParam() {
        return deepCopy(robotParam);
    }

    private static AcpRobotParam deepCopy(AcpRobotParam source) {
        if (source == null) {
            throw new IllegalArgumentException("robotParam must not be null");
        }
        return GSON.fromJson(GSON.toJson(source), AcpRobotParam.class);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
