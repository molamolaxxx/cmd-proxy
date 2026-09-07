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
    private String mode;
    private String captainTeamMemberId;
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
    private boolean mixedPlacement;
    private List<TeamContactRef> roster;

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
                timestamp, timestamp, null, null, null, false, null,
                TeamMode.NORMAL.name(), null);
    }

    public static TeamDefinition creating(String teamId, String ownerChatterId,
                                          String name, String transportGroup,
                                          String createRequestId,
                                          List<TeamMemberDefinition> members,
                                          boolean mixedPlacement,
                                          List<TeamContactRef> roster,
                                          long timestamp) {
        return new TeamDefinition(SCHEMA_VERSION, teamId, ownerChatterId, name,
                TeamState.CREATING, 1L, transportGroup, createRequestId, members,
                timestamp, timestamp, null, null, null, mixedPlacement, roster,
                TeamMode.NORMAL.name(), null);
    }

    public static TeamDefinition creating(String teamId, String ownerChatterId,
                                          String name, String transportGroup,
                                          String createRequestId,
                                          List<TeamMemberDefinition> members,
                                          boolean mixedPlacement,
                                          List<TeamContactRef> roster,
                                          TeamMode mode,
                                          String captainTeamMemberId,
                                          long timestamp) {
        return new TeamDefinition(SCHEMA_VERSION, teamId, ownerChatterId, name,
                TeamState.CREATING, 1L, transportGroup, createRequestId, members,
                timestamp, timestamp, null, null, null, mixedPlacement, roster,
                Objects.requireNonNull(mode, "mode").name(), captainTeamMemberId);
    }

    private TeamDefinition(String schemaVersion, String teamId, String ownerChatterId,
                           String name, TeamState state, long version,
                           String transportGroup, String createRequestId,
                           List<TeamMemberDefinition> members, long createdAt,
                           long updatedAt, Long deletedAt, TeamError lastError,
                           String deleteRequestId, boolean mixedPlacement,
                           List<TeamContactRef> roster, String mode,
                           String captainTeamMemberId) {
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
        this.mixedPlacement = mixedPlacement;
        this.roster = copyRoster(roster, this.members);
        this.mode = TeamMode.fromWire(mode).name();
        this.captainTeamMemberId = normalizeCaptain(captainTeamMemberId);
        validateTopology();
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
                createdAt, timestamp, newDeletedAt, error, deleteRequestId,
                mixedPlacement, roster, mode, captainTeamMemberId);
    }

    public TeamDefinition withMembers(List<TeamMemberDefinition> newMembers, long timestamp) {
        if (state.isTerminal()) {
            throw new IllegalStateException("terminal team cannot update members");
        }
        return new TeamDefinition(schemaVersion, teamId, ownerChatterId, name,
                state, version + 1L, transportGroup, createRequestId, newMembers,
                createdAt, timestamp, deletedAt, lastError, deleteRequestId,
                mixedPlacement, roster, mode, captainTeamMemberId);
    }

    public TeamDefinition withTransportGroup(String newTransportGroup, long timestamp) {
        if (state.isTerminal()) {
            throw new IllegalStateException("terminal team cannot update transportGroup");
        }
        return new TeamDefinition(schemaVersion, teamId, ownerChatterId, name,
                state, version + 1L, newTransportGroup, createRequestId, members,
                createdAt, timestamp, deletedAt, lastError, deleteRequestId,
                mixedPlacement, roster, mode, captainTeamMemberId);
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
                createdAt, timestamp, newDeletedAt, error, deleteRequestId,
                mixedPlacement, roster, mode, captainTeamMemberId);
    }

    public TeamDefinition beginDeleting(String requestId, long timestamp) {
        if (state.isTerminal() || state == TeamState.DELETING) {
            throw new IllegalStateException("team cannot begin deleting from " + state);
        }
        return new TeamDefinition(schemaVersion, teamId, ownerChatterId, name,
                TeamState.DELETING, version + 1L, transportGroup, createRequestId,
                members, createdAt, timestamp, deletedAt, null,
                TeamError.requireText(requestId, "deleteRequestId"),
                mixedPlacement, roster, mode, captainTeamMemberId);
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

    private static List<TeamContactRef> copyRoster(List<TeamContactRef> roster,
                                                    List<TeamMemberDefinition> members) {
        List<TeamContactRef> result = new ArrayList<>();
        if (roster == null || roster.isEmpty()) {
            Set<Integer> sourceOrders = new HashSet<>();
            boolean uniqueSourceOrders = true;
            for (TeamMemberDefinition member : members) {
                if (!sourceOrders.add(member.getOrder())) uniqueSourceOrders = false;
            }
            int fallbackOrder = 0;
            for (TeamMemberDefinition member : members) {
                result.add(new TeamContactRef(member.getTeamMemberId(),
                        member.getAcpClientId(), member.getDisplayName(),
                        member.getRemark(), uniqueSourceOrders
                                ? member.getOrder() : fallbackOrder++));
            }
        } else {
            result.addAll(roster);
        }
        if (result.isEmpty() || result.size() > 6) {
            throw new IllegalArgumentException("roster size must be between 1 and 6");
        }
        Set<String> ids = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (TeamContactRef contact : result) {
            if (contact == null || !ids.add(contact.getTargetTeamMemberId())
                    || !orders.add(contact.getOrder())) {
                throw new IllegalArgumentException("roster ids and orders must be unique");
            }
        }
        return result;
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

    public TeamMode getMode() { return TeamMode.fromWire(mode); }

    public String getCaptainTeamMemberId() { return captainTeamMemberId; }

    public boolean isCaptainMode() { return getMode() == TeamMode.CAPTAIN; }

    public boolean isCaptain(String teamMemberId) {
        return isCaptainMode() && captainTeamMemberId != null
                && captainTeamMemberId.equals(teamMemberId);
    }

    public boolean canCommunicate(String senderTeamMemberId,
                                  String targetTeamMemberId) {
        if (senderTeamMemberId == null || targetTeamMemberId == null
                || senderTeamMemberId.equals(targetTeamMemberId)) return false;
        if (!isCaptainMode()) return true;
        return isCaptain(senderTeamMemberId) || isCaptain(targetTeamMemberId);
    }

    /** External channels and task systems enter a captain Team through its captain. */
    public boolean isBusinessEntryMember(String teamMemberId) {
        return !isCaptainMode() || isCaptain(teamMemberId);
    }

    /** Re-validates Gson-restored definitions before they become authoritative. */
    public void validateTopology() {
        TeamMode effectiveMode = TeamMode.fromWire(mode);
        String captain = normalizeCaptain(captainTeamMemberId);
        // Gson bypasses constructors when restoring legacy data; normalize once so every
        // subsequent list/get/event projection explicitly carries the compatibility mode.
        this.mode = effectiveMode.name();
        this.captainTeamMemberId = captain;
        if (effectiveMode == TeamMode.NORMAL) {
            if (captain != null) {
                throw new IllegalArgumentException(
                        "NORMAL Team must not define captainTeamMemberId");
            }
            return;
        }
        List<TeamContactRef> globalRoster = getRoster();
        if (captain == null) {
            throw new IllegalArgumentException("CAPTAIN_REQUIRED: captainTeamMemberId is required");
        }
        if (globalRoster.size() < 2) {
            throw new IllegalArgumentException(
                    "CAPTAIN Team must contain a captain and at least one member");
        }
        for (TeamContactRef contact : globalRoster) {
            if (captain.equals(contact.getTargetTeamMemberId())) return;
        }
        throw new IllegalArgumentException(
                "CAPTAIN_REQUIRED: captainTeamMemberId must belong to Team roster");
    }

    private static String normalizeCaptain(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
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

    public boolean isMixedPlacement() { return mixedPlacement; }

    public List<TeamContactRef> getRoster() {
        if (roster == null || roster.isEmpty()) {
            return Collections.unmodifiableList(copyRoster(null, members));
        }
        return Collections.unmodifiableList(roster);
    }
}
