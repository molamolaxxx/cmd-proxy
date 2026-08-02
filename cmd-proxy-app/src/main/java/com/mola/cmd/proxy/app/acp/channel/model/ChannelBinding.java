package com.mola.cmd.proxy.app.acp.channel.model;

public class ChannelBinding {
    public static final String TYPE_MAIN = "MAIN";
    public static final String TYPE_TEAM_MEMBER = "TEAM_MEMBER";

    private String type;
    private String instanceId;
    private String groupId;
    private String teamId;
    private String teamMemberId;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getTeamId() { return teamId; }
    public void setTeamId(String teamId) { this.teamId = teamId; }
    public String getTeamMemberId() { return teamMemberId; }
    public void setTeamMemberId(String teamMemberId) { this.teamMemberId = teamMemberId; }
}
