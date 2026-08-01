package com.mola.cmd.proxy.app.acp.team.protocol;

public final class TeamDeleteCommand {

    private String schemaVersion;
    private String requestId;
    private String ownerChatterId;
    private String teamId;
    private Long expectedVersion;

    @SuppressWarnings("unused")
    private TeamDeleteCommand() {
    }

    public TeamDeleteCommand(String schemaVersion, String requestId,
                             String ownerChatterId, String teamId,
                             Long expectedVersion) {
        this.schemaVersion = schemaVersion;
        this.requestId = requestId;
        this.ownerChatterId = ownerChatterId;
        this.teamId = teamId;
        this.expectedVersion = expectedVersion;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getOwnerChatterId() {
        return ownerChatterId;
    }

    public String getTeamId() {
        return teamId;
    }

    public Long getExpectedVersion() {
        return expectedVersion;
    }
}
