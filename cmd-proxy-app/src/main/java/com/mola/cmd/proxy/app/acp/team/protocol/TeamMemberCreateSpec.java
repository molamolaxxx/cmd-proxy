package com.mola.cmd.proxy.app.acp.team.protocol;

public final class TeamMemberCreateSpec {

    private String teamMemberId;
    private String sourceRobotId;
    private String sourceGroupId;
    private int order;
    private String remark;

    @SuppressWarnings("unused")
    private TeamMemberCreateSpec() {
    }

    public TeamMemberCreateSpec(String teamMemberId, String sourceRobotId,
                                String sourceGroupId, int order) {
        this(teamMemberId, sourceRobotId, sourceGroupId, order, null);
    }

    public TeamMemberCreateSpec(String teamMemberId, String sourceRobotId,
                                String sourceGroupId, int order, String remark) {
        this.teamMemberId = teamMemberId;
        this.sourceRobotId = sourceRobotId;
        this.sourceGroupId = sourceGroupId;
        this.order = order;
        this.remark = remark;
    }

    public String getTeamMemberId() {
        return teamMemberId;
    }

    public String getSourceRobotId() {
        return sourceRobotId;
    }

    public String getSourceGroupId() {
        return sourceGroupId;
    }

    public int getOrder() {
        return order;
    }

    public String getRemark() {
        return remark;
    }
}
