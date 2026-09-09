package com.mola.cmd.proxy.app.acp.gateway.model;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;

/** Stable surface-aware address selected by a logical gateway. */
public final class GatewayTarget {
    public static final String MOLACHAT_MAIN = "MOLACHAT_MAIN";
    public static final String STARWEAVE_MAIN = "STARWEAVE_MAIN";
    public static final String TEAM_MEMBER = "TEAM_MEMBER";

    private String type;
    private String instanceId;
    private String ownerId;
    private String groupId;
    private String robotId;
    private String teamId;
    private String teamMemberId;
    private String acpClientId;

    public String getType() { return upper(type); }
    public void setType(String type) { this.type = type; }
    public String getInstanceId() { return trim(instanceId); }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getOwnerId() { return trim(ownerId); }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getGroupId() { return trim(groupId); }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getRobotId() { return trim(robotId); }
    public void setRobotId(String robotId) { this.robotId = robotId; }
    public String getTeamId() { return trim(teamId); }
    public void setTeamId(String teamId) { this.teamId = teamId; }
    public String getTeamMemberId() { return trim(teamMemberId); }
    public void setTeamMemberId(String teamMemberId) { this.teamMemberId = teamMemberId; }
    public String getAcpClientId() { return trim(acpClientId); }
    public void setAcpClientId(String acpClientId) { this.acpClientId = acpClientId; }

    public String key() {
        if (TEAM_MEMBER.equals(getType())) {
            return TEAM_MEMBER + ":" + getInstanceId() + ":" + getTeamId()
                    + ":" + getTeamMemberId();
        }
        return getType() + ":" + getInstanceId() + ":" + getGroupId();
    }

    public boolean matches(AcpClientIdentity identity) {
        if (identity == null) return false;
        if (TEAM_MEMBER.equals(getType())) {
            return identity.isTeam()
                    && getTeamId().equals(identity.getTeamId())
                    && getTeamMemberId().equals(identity.getTeamMemberId());
        }
        if (!getGroupId().equals(identity.getLogicalId())) return false;
        if (STARWEAVE_MAIN.equals(getType())) return identity.isStarweave();
        return MOLACHAT_MAIN.equals(getType())
                && identity.getSurface() == com.mola.cmd.proxy.app.acp.acpclient.ClientSurface.MOLACHAT;
    }

    public void validate(String currentInstanceId) {
        String targetType = getType();
        if (!MOLACHAT_MAIN.equals(targetType) && !STARWEAVE_MAIN.equals(targetType)
                && !TEAM_MEMBER.equals(targetType)) {
            throw new IllegalArgumentException("unsupported Agent gateway target type: " + targetType);
        }
        if (!getInstanceId().isEmpty() && !getInstanceId().equals(currentInstanceId)) {
            throw new IllegalArgumentException("Agent gateway target belongs to another instance");
        }
        if (TEAM_MEMBER.equals(targetType)) {
            require(getTeamId(), "target.teamId");
            require(getTeamMemberId(), "target.teamMemberId");
        } else {
            require(getGroupId(), "target.groupId");
        }
    }

    private static void require(String value, String name) {
        if (value.isEmpty()) throw new IllegalArgumentException(name + " is required");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String upper(String value) {
        return trim(value).toUpperCase(java.util.Locale.ROOT);
    }
}
