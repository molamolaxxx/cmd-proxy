package com.mola.cmd.proxy.app.acp.team.runtime;

import java.util.Objects;

public final class TeamClientKey {

    private final String teamId;
    private final String teamMemberId;

    public TeamClientKey(String teamId, String teamMemberId) {
        this.teamId = requireText(teamId, "teamId");
        this.teamMemberId = requireText(teamMemberId, "teamMemberId");
    }

    public String getTeamId() {
        return teamId;
    }

    public String getTeamMemberId() {
        return teamMemberId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TeamClientKey)) {
            return false;
        }
        TeamClientKey that = (TeamClientKey) other;
        return teamId.equals(that.teamId) && teamMemberId.equals(that.teamMemberId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(teamId, teamMemberId);
    }

    @Override
    public String toString() {
        return teamId + "/" + teamMemberId;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
