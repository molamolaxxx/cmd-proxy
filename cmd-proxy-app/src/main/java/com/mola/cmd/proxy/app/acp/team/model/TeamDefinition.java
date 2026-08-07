package com.mola.cmd.proxy.app.acp.team.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class TeamDefinition {

    public static final String SCHEMA_VERSION = "1";

    private String schemaVersion;
    private String teamId;
    private String ownerChatterId;
    private String name;
    private TeamState state;
    private long version;
    private String transportGroup;
    private String createRequestId;
    private String deleteRequestId;
    private List<TeamMemberDefinition> members;
    private long createdAt;
    private long updatedAt;
    private Long deletedAt;
    private TeamError lastError;

    @SuppressWarnings("unused")
    private TeamDefinition() {
    }

    public static TeamDefinition creating(String teamId, String ownerChatterId,
                                          String name, String transportGroup,
                                          String createRequestId,
                                          List<TeamMemberDefinition> members,
                                          long timestamp) {
        return new TeamDefinition(SCHEMA_VERSION, teamId, ownerChatterId, name,
                TeamState.CREATING, 1L, transportGroup, createRequestId, members,
                timestamp, timestamp, null, null, null);
    }

    private TeamDefinition(String schemaVersion, String teamId, String ownerChatterId,
                           String name, TeamState state, long version,
                           String transportGroup, String createRequestId,
                           List<TeamMemberDefinition> members, long createdAt,
                           long updatedAt, Long deletedAt, TeamError lastError,
                           String deleteRequestId) {
        this.schemaVersion = TeamError.requireText(schemaVersion, "schemaVersion");
        this.teamId = TeamError.requireText(teamId, "teamId");
        this.ownerChatterId = TeamError.requireText(ownerChatterId, "ownerChatterId");
        this.name = TeamError.requireText(name, "name");
        this.state = Objects.requireNonNull(state, "state");
        if (version < 1L) {
            throw new IllegalArgumentException("version must be positive");
        }
        this.version = version;
        this.transportGroup = TeamError.requireText(transportGroup, "transportGroup");
        this.createRequestId = TeamError.requireText(createRequestId, "createRequestId");
        this.members = copyAndValidateMembers(members);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
        this.lastError = lastError;
        this.deleteRequestId = deleteRequestId;
    }

    public TeamDefinition transitionTo(TeamState newState, TeamError error, long timestamp) {
        Objects.requireNonNull(newState, "newState");
        if (state.isTerminal()) {
            throw new IllegalStateException("terminal team cannot transition: " + state);
        }
        Long newDeletedAt = deletedAt;
        if (newState.isTerminal()) {
            newDeletedAt = Long.valueOf(timestamp);
        }
        return new TeamDefinition(schemaVersion, teamId, ownerChatterId, name,
                newState, version + 1L, transportGroup, createRequestId, members,
                createdAt, timestamp, newDeletedAt, error, deleteRequestId);
    }

    public TeamDefinition withMembers(List<TeamMemberDefinition> newMembers, long timestamp) {
        if (state.isTerminal()) {
            throw new IllegalStateException("terminal team cannot update members");
        }
        return new TeamDefinition(schemaVersion, teamId, ownerChatterId, name,
                state, version + 1L, transportGroup, createRequestId, newMembers,
                createdAt, timestamp, deletedAt, lastError, deleteRequestId);
    }

    public TeamDefinition withTransportGroup(String newTransportGroup, long timestamp) {
        if (state.isTerminal()) {
            throw new IllegalStateException("terminal team cannot update transportGroup");
        }
        return new TeamDefinition(schemaVersion, teamId, ownerChatterId, name,
                state, version + 1L, newTransportGroup, createRequestId, members,
                createdAt, timestamp, deletedAt, lastError, deleteRequestId);
    }

    public TeamDefinition transitionWithMembers(TeamState newState,
                                                List<TeamMemberDefinition> newMembers,
                                                TeamError error, long timestamp) {
        Objects.requireNonNull(newState, "newState");
        if (state.isTerminal()) {
            throw new IllegalStateException("terminal team cannot transition: " + state);
        }
        Long newDeletedAt = newState.isTerminal() ? Long.valueOf(timestamp) : deletedAt;
        return new TeamDefinition(schemaVersion, teamId, ownerChatterId, name,
                newState, version + 1L, transportGroup, createRequestId, newMembers,
                createdAt, timestamp, newDeletedAt, error, deleteRequestId);
    }

    public TeamDefinition beginDeleting(String requestId, long timestamp) {
        if (state.isTerminal() || state == TeamState.DELETING) {
            throw new IllegalStateException("team cannot begin deleting from " + state);
        }
        return new TeamDefinition(schemaVersion, teamId, ownerChatterId, name,
                TeamState.DELETING, version + 1L, transportGroup, createRequestId,
                members, createdAt, timestamp, deletedAt, null,
                TeamError.requireText(requestId, "deleteRequestId"));
    }

    private static List<TeamMemberDefinition> copyAndValidateMembers(
            List<TeamMemberDefinition> members) {
        if (members == null || members.isEmpty() || members.size() > 6) {
            throw new IllegalArgumentException("members size must be between 1 and 6");
        }
        List<TeamMemberDefinition> copy = new ArrayList<>(members);
        Set<String> ids = new HashSet<>();
        for (TeamMemberDefinition member : copy) {
            if (member == null || !ids.add(member.getTeamMemberId())) {
                throw new IllegalArgumentException("teamMemberId must be unique");
            }
        }
        return copy;
    }

    public String getSchemaVersion() {
        return schemaVersion;
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

    public TeamState getState() {
        return state;
    }

    public long getVersion() {
        return version;
    }

    public String getTransportGroup() {
        return transportGroup;
    }

    public String getCreateRequestId() {
        return createRequestId;
    }

    public String getDeleteRequestId() {
        return deleteRequestId;
    }

    public List<TeamMemberDefinition> getMembers() {
        return Collections.unmodifiableList(members);
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public TeamError getLastError() {
        return lastError;
    }
}
