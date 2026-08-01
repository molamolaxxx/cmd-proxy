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
    private List<TeamMemberCreateSpec> members;

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

    public List<TeamMemberCreateSpec> getMembers() {
        return members == null ? null : Collections.unmodifiableList(members);
    }
}
