package com.mola.cmd.proxy.app.acp.team.protocol;

/**
 * Fast Team 可选来源的非敏感 discovery 投影。
 */
public final class TeamMemberSourceDescriptor {

    private final String ownerChatterId;
    private final String sourceGroupId;
    private final String sourceRobotId;
    private final String robotName;
    private final String displayName;
    private final String avatar;
    private final String remark;
    private final boolean onlyTeamMember;

    public TeamMemberSourceDescriptor(String ownerChatterId, String sourceGroupId,
                                      String sourceRobotId, String robotName,
                                      String displayName, String avatar, String remark,
                                      boolean onlyTeamMember) {
        this.ownerChatterId = requireText(ownerChatterId, "ownerChatterId");
        this.sourceGroupId = requireText(sourceGroupId, "sourceGroupId");
        this.sourceRobotId = requireText(sourceRobotId, "sourceRobotId");
        this.robotName = requireText(robotName, "robotName");
        this.displayName = requireText(displayName, "displayName");
        this.avatar = avatar == null ? "" : avatar;
        this.remark = remark == null ? "" : remark;
        this.onlyTeamMember = onlyTeamMember;
    }

    public String getOwnerChatterId() {
        return ownerChatterId;
    }

    public String getSourceGroupId() {
        return sourceGroupId;
    }

    public String getSourceRobotId() {
        return sourceRobotId;
    }

    public String getRobotName() {
        return robotName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAvatar() {
        return avatar;
    }

    public String getRemark() {
        return remark;
    }

    public boolean isOnlyTeamMember() {
        return onlyTeamMember;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
