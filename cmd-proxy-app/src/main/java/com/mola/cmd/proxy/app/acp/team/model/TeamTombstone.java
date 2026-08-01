package com.mola.cmd.proxy.app.acp.team.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TeamTombstone {

    private String teamId;
    private String deleteRequestId;
    private TeamState finalState;
    private long deletedAt;
    private long expireAt;
    private List<TeamError> warnings;

    @SuppressWarnings("unused")
    private TeamTombstone() {
    }

    public TeamTombstone(String teamId, String deleteRequestId, TeamState finalState,
                         long deletedAt, long expireAt, List<TeamError> warnings) {
        this.teamId = TeamError.requireText(teamId, "teamId");
        this.deleteRequestId = TeamError.requireText(deleteRequestId, "deleteRequestId");
        if (finalState != TeamState.DELETED
                && finalState != TeamState.DELETED_WITH_WARNINGS) {
            throw new IllegalArgumentException("tombstone finalState must be terminal");
        }
        this.finalState = finalState;
        this.deletedAt = deletedAt;
        this.expireAt = expireAt;
        this.warnings = warnings == null
                ? Collections.emptyList() : new ArrayList<>(warnings);
    }

    public String getTeamId() {
        return teamId;
    }

    public String getDeleteRequestId() {
        return deleteRequestId;
    }

    public TeamState getFinalState() {
        return finalState;
    }

    public long getDeletedAt() {
        return deletedAt;
    }

    public long getExpireAt() {
        return expireAt;
    }

    public List<TeamError> getWarnings() {
        return Collections.unmodifiableList(warnings == null
                ? Collections.emptyList() : warnings);
    }
}
