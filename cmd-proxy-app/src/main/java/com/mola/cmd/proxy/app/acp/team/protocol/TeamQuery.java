package com.mola.cmd.proxy.app.acp.team.protocol;

public final class TeamQuery {

    private String schemaVersion;
    private String ownerChatterId;
    private String teamId;
    private Long sinceVersion;

    @SuppressWarnings("unused")
    private TeamQuery() {
    }

    public TeamQuery(String schemaVersion, String ownerChatterId,
                     String teamId, Long sinceVersion) {
        this.schemaVersion = schemaVersion;
        this.ownerChatterId = ownerChatterId;
        this.teamId = teamId;
        this.sinceVersion = sinceVersion;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getOwnerChatterId() {
        return ownerChatterId;
    }

    public String getTeamId() {
        return teamId;
    }

    public Long getSinceVersion() {
        return sinceVersion;
    }
}
