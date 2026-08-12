package com.mola.cmd.proxy.app.acp.team.protocol;

/** Instance-scoped projection of a robot explicitly shared with another owner. */
public final class RemoteTeamMemberSourceDescriptor {
    private final String granteeOwnerChatterId;
    private final String participantInstanceId;
    private final String sourceGroupId;
    private final String sourceRobotId;
    private final String robotName;
    private final String displayName;
    private final String avatar;
    private final String remark;

    public RemoteTeamMemberSourceDescriptor(String granteeOwnerChatterId,
                                            String participantInstanceId,
                                            String sourceGroupId, String sourceRobotId,
                                            String robotName, String displayName,
                                            String avatar, String remark) {
        this.granteeOwnerChatterId = requireText(granteeOwnerChatterId, "granteeOwnerChatterId");
        this.participantInstanceId = requireText(participantInstanceId, "participantInstanceId");
        this.sourceGroupId = requireText(sourceGroupId, "sourceGroupId");
        this.sourceRobotId = requireText(sourceRobotId, "sourceRobotId");
        this.robotName = requireText(robotName, "robotName");
        this.displayName = requireText(displayName, "displayName");
        this.avatar = avatar == null ? "" : avatar;
        this.remark = remark == null ? "" : remark;
    }

    public String getGranteeOwnerChatterId() { return granteeOwnerChatterId; }
    public String getParticipantInstanceId() { return participantInstanceId; }
    public String getSourceGroupId() { return sourceGroupId; }
    public String getSourceRobotId() { return sourceRobotId; }
    public String getRobotName() { return robotName; }
    public String getDisplayName() { return displayName; }
    public String getAvatar() { return avatar; }
    public String getRemark() { return remark; }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
