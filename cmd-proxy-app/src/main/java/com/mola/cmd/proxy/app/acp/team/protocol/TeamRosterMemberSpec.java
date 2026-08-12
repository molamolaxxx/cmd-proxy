package com.mola.cmd.proxy.app.acp.team.protocol;

public final class TeamRosterMemberSpec {
    private String teamMemberId;
    private String acpClientId;
    private String displayName;
    private String remark;
    private int order;

    @SuppressWarnings("unused")
    private TeamRosterMemberSpec() { }

    public TeamRosterMemberSpec(String teamMemberId, String acpClientId,
                                String displayName, String remark, int order) {
        this.teamMemberId = teamMemberId;
        this.acpClientId = acpClientId;
        this.displayName = displayName;
        this.remark = remark;
        this.order = order;
    }

    public String getTeamMemberId() { return teamMemberId; }
    public String getAcpClientId() { return acpClientId; }
    public String getDisplayName() { return displayName; }
    public String getRemark() { return remark; }
    public int getOrder() { return order; }
}
