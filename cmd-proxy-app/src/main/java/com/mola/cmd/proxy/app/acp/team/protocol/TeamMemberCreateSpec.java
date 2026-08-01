package com.mola.cmd.proxy.app.acp.team.protocol;

public final class TeamMemberCreateSpec {

    private String teamMemberId;
    private String sourceRobotId;
    private String sourceGroupId;
    private int order;

    @SuppressWarnings("unused")
    private TeamMemberCreateSpec() {
    }

    public TeamMemberCreateSpec(String teamMemberId, String sourceRobotId,
                                String sourceGroupId, int order) {
        this.teamMemberId = teamMemberId;
        this.sourceRobotId = sourceRobotId;
        this.sourceGroupId = sourceGroupId;
        this.order = order;
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
}
