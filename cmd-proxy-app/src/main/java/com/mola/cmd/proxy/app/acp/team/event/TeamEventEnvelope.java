package com.mola.cmd.proxy.app.acp.team.event;

import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime;

import java.util.UUID;

public final class TeamEventEnvelope {

    private final String schemaVersion;
    private final String eventId;
    private final long eventSeq;
    private final String transportGroup;
    private final String teamId;
    private final String teamMemberId;
    private final String acpClientId;
    private final TeamEventType type;
    private final long teamVersion;
    private final long timestamp;
    private final Object data;

    private TeamEventEnvelope(String eventId, long eventSeq, String transportGroup,
                              String teamId, String teamMemberId, String acpClientId,
                              TeamEventType type, long teamVersion, long timestamp,
                              Object data) {
        this.schemaVersion = TeamDefinition.SCHEMA_VERSION;
        this.eventId = eventId;
        this.eventSeq = eventSeq;
        this.transportGroup = transportGroup;
        this.teamId = teamId;
        this.teamMemberId = teamMemberId;
        this.acpClientId = acpClientId;
        this.type = type;
        this.teamVersion = teamVersion;
        this.timestamp = timestamp;
        this.data = data;
    }

    public static TeamEventEnvelope next(TeamRuntime runtime, String teamMemberId,
                                         String acpClientId, TeamEventType type,
                                         Object data) {
        TeamDefinition definition = runtime.getDefinition();
        return new TeamEventEnvelope(
                UUID.randomUUID().toString(), runtime.nextEventSeq(),
                definition.getTransportGroup(), definition.getTeamId(),
                teamMemberId, acpClientId, type, definition.getVersion(),
                System.currentTimeMillis(), data);
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getEventId() {
        return eventId;
    }

    public long getEventSeq() {
        return eventSeq;
    }

    public String getTransportGroup() {
        return transportGroup;
    }

    public String getTeamId() {
        return teamId;
    }

    public String getTeamMemberId() {
        return teamMemberId;
    }

    public String getAcpClientId() {
        return acpClientId;
    }

    public TeamEventType getType() {
        return type;
    }

    public long getTeamVersion() {
        return teamVersion;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Object getData() {
        return data;
    }
}
