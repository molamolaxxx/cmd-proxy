package com.mola.cmd.proxy.app.acp.team.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Complete desired local roster for an existing Team. */
public final class TeamMembersUpdateCommand {
    private final String schemaVersion;
    private final String requestId;
    private final String ownerChatterId;
    private final String teamId;
    private final Long expectedVersion;
    private final List<TeamMemberCreateSpec> members;

    public TeamMembersUpdateCommand(String schemaVersion, String requestId,
                                    String ownerChatterId, String teamId,
                                    Long expectedVersion,
                                    List<TeamMemberCreateSpec> members) {
        this.schemaVersion = schemaVersion;
        this.requestId = requestId;
        this.ownerChatterId = ownerChatterId;
        this.teamId = teamId;
        this.expectedVersion = expectedVersion;
        this.members = members == null ? null : new ArrayList<>(members);
    }

    public String getSchemaVersion() { return schemaVersion; }
    public String getRequestId() { return requestId; }
    public String getOwnerChatterId() { return ownerChatterId; }
    public String getTeamId() { return teamId; }
    public Long getExpectedVersion() { return expectedVersion; }
    public List<TeamMemberCreateSpec> getMembers() {
        return members == null ? null : Collections.unmodifiableList(members);
    }
}
