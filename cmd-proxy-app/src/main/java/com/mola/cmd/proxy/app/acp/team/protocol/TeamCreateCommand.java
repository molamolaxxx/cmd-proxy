package com.mola.cmd.proxy.app.acp.team.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TeamCreateCommand {

    private String schemaVersion;
    private String requestId;
    private String teamId;
    private String ownerChatterId;
    private String name;
    private String mode;
    private String captainTeamMemberId;
    private List<TeamMemberCreateSpec> members;
    private boolean mixedPlacement;
    private List<TeamRosterMemberSpec> roster;

    @SuppressWarnings("unused")
    private TeamCreateCommand() {
    }

    public TeamCreateCommand(String schemaVersion, String requestId, String teamId,
                             String ownerChatterId, String name,
                             List<TeamMemberCreateSpec> members) {
        this.schemaVersion = schemaVersion;
        this.requestId = requestId;
        this.teamId = teamId;
        this.ownerChatterId = ownerChatterId;
        this.name = name;
        this.members = members == null ? null : new ArrayList<>(members);
    }

    public TeamCreateCommand(String schemaVersion, String requestId, String teamId,
                             String ownerChatterId, String name,
                             List<TeamMemberCreateSpec> members, String mode,
                             String captainTeamMemberId) {
        this(schemaVersion, requestId, teamId, ownerChatterId, name, members);
        this.mode = mode;
        this.captainTeamMemberId = captainTeamMemberId;
    }

    public TeamCreateCommand(String schemaVersion, String requestId, String teamId,
                             String ownerChatterId, String name,
                             List<TeamMemberCreateSpec> members,
                             boolean mixedPlacement,
                             List<TeamRosterMemberSpec> roster) {
        this(schemaVersion, requestId, teamId, ownerChatterId, name, members);
        this.mixedPlacement = mixedPlacement;
        this.roster = roster == null ? null : new ArrayList<>(roster);
    }

    public TeamCreateCommand(String schemaVersion, String requestId, String teamId,
                             String ownerChatterId, String name,
                             List<TeamMemberCreateSpec> members,
                             boolean mixedPlacement,
                             List<TeamRosterMemberSpec> roster,
                             String mode, String captainTeamMemberId) {
        this(schemaVersion, requestId, teamId, ownerChatterId, name, members,
                mixedPlacement, roster);
        this.mode = mode;
        this.captainTeamMemberId = captainTeamMemberId;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTeamId() {
        return teamId;
    }

    public String getOwnerChatterId() {
        return ownerChatterId;
    }

    public String getName() {
        return name;
    }

    public String getMode() { return mode; }

    public String getCaptainTeamMemberId() { return captainTeamMemberId; }

    public List<TeamMemberCreateSpec> getMembers() {
        return members == null ? null : Collections.unmodifiableList(members);
    }

    public boolean isMixedPlacement() { return mixedPlacement; }

    public List<TeamRosterMemberSpec> getRoster() {
        return roster == null ? null : Collections.unmodifiableList(roster);
    }
}
