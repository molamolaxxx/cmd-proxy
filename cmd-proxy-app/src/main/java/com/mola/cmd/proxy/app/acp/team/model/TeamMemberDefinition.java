package com.mola.cmd.proxy.app.acp.team.model;

import java.util.Objects;

public final class TeamMemberDefinition {

    public static final String ROBOT_GROUP = "team-acp";

    private String teamMemberId;
    private String robotId;
    private String acpClientId;
    private String robotGroup;
    private String sourceRobotId;
    private String sourceGroupId;
    private String sourceRobotName;
    private String displayName;
    private String avatar;
    private int order;
    private String remark;
    private String configFingerprint;
    private TeamMemberState state;
    private String sessionId;
    private TeamError lastError;

    @SuppressWarnings("unused")
    private TeamMemberDefinition() {
    }

    public TeamMemberDefinition(String teamMemberId, String sourceGroupId,
                                String sourceRobotName, String displayName,
                                String remark, String configFingerprint) {
        this(teamMemberId, "team-acp-" + TeamError.requireText(teamMemberId, "teamMemberId"),
                "team-acp-" + teamMemberId, ROBOT_GROUP, "acp-" + sourceRobotName,
                sourceGroupId, sourceRobotName, displayName, "", 0,
                remark, configFingerprint,
                TeamMemberState.STARTING, null, null);
    }

    public TeamMemberDefinition(String teamMemberId, String sourceRobotId,
                                String sourceGroupId, String sourceRobotName,
                                String displayName, String avatar, int order,
                                String remark, String configFingerprint) {
        this(teamMemberId, "team-acp-" + TeamError.requireText(teamMemberId, "teamMemberId"),
                "team-acp-" + teamMemberId, ROBOT_GROUP, sourceRobotId,
                sourceGroupId, sourceRobotName, displayName, avatar, order,
                remark, configFingerprint, TeamMemberState.STARTING, null, null);
    }

    private TeamMemberDefinition(String teamMemberId, String robotId, String acpClientId,
                                 String robotGroup, String sourceRobotId, String sourceGroupId,
                                 String sourceRobotName, String displayName,
                                 String avatar, int order, String remark, String configFingerprint,
                                 TeamMemberState state, String sessionId,
                                 TeamError lastError) {
        this.teamMemberId = TeamError.requireText(teamMemberId, "teamMemberId");
        this.robotId = TeamError.requireText(robotId, "robotId");
        this.acpClientId = TeamError.requireText(acpClientId, "acpClientId");
        this.robotGroup = TeamError.requireText(robotGroup, "robotGroup");
        this.sourceRobotId = TeamError.requireText(sourceRobotId, "sourceRobotId");
        this.sourceGroupId = TeamError.requireText(sourceGroupId, "sourceGroupId");
        this.sourceRobotName = TeamError.requireText(sourceRobotName, "sourceRobotName");
        this.displayName = TeamError.requireText(displayName, "displayName");
        this.avatar = avatar == null ? "" : avatar;
        if (order < 0) {
            throw new IllegalArgumentException("order must not be negative");
        }
        this.order = order;
        this.remark = remark == null ? "" : remark;
        this.configFingerprint = TeamError.requireText(configFingerprint, "configFingerprint");
        this.state = Objects.requireNonNull(state, "state");
        this.sessionId = sessionId;
        this.lastError = lastError;
    }

    public TeamMemberDefinition withState(TeamMemberState newState, String newSessionId,
                                          TeamError error) {
        return new TeamMemberDefinition(teamMemberId, robotId, acpClientId, robotGroup,
                sourceRobotId, sourceGroupId, sourceRobotName, displayName, avatar, order,
                remark, configFingerprint, newState, newSessionId, error);
    }

    public String getTeamMemberId() {
        return teamMemberId;
    }

    public String getRobotId() {
        return robotId;
    }

    public String getAcpClientId() {
        return acpClientId;
    }

    public String getRobotGroup() {
        return robotGroup;
    }

    public String getSourceRobotId() {
        return sourceRobotId;
    }

    public String getSourceGroupId() {
        return sourceGroupId;
    }

    public String getSourceRobotName() {
        return sourceRobotName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAvatar() {
        return avatar;
    }

    public int getOrder() {
        return order;
    }

    public String getRemark() {
        return remark;
    }

    public String getConfigFingerprint() {
        return configFingerprint;
    }

    public TeamMemberState getState() {
        return state;
    }

    public String getSessionId() {
        return sessionId;
    }

    public TeamError getLastError() {
        return lastError;
    }
}
