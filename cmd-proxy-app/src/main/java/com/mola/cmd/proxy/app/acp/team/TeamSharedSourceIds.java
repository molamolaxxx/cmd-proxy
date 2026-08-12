package com.mola.cmd.proxy.app.acp.team;

public final class TeamSharedSourceIds {
    public static final String PREFIX = "team-shared-";

    private TeamSharedSourceIds() { }

    public static String groupId(String instanceId, String sourceRobotId) {
        return PREFIX + require(instanceId) + "-" + require(sourceRobotId);
    }

    public static boolean isSharedGroup(String sourceGroupId) {
        return sourceGroupId != null && sourceGroupId.startsWith(PREFIX);
    }

    private static String require(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("shared source id component must not be blank");
        }
        return value.trim();
    }
}
