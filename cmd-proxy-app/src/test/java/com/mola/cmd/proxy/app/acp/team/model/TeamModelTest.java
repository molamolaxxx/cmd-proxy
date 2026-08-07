package com.mola.cmd.proxy.app.acp.team.model;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class TeamModelTest {

    @Test
    public void memberUsesStableUuidDerivedIdsAndLogicalGroup() {
        TeamMemberDefinition member = member("member-1", "Robot One");

        assertEquals("team-acp-member-1", member.getRobotId());
        assertEquals(member.getRobotId(), member.getAcpClientId());
        assertEquals("team-acp", member.getRobotGroup());
        assertEquals(TeamMemberState.STARTING, member.getState());
    }

    @Test
    public void creatingAndTransitionIncrementVersionWithoutMutation() {
        TeamDefinition creating = definition();
        TeamDefinition ready = creating.transitionTo(TeamState.READY, null, 200L);

        assertEquals(TeamState.CREATING, creating.getState());
        assertEquals(1L, creating.getVersion());
        assertEquals(TeamState.READY, ready.getState());
        assertEquals(2L, ready.getVersion());
        assertEquals(200L, ready.getUpdatedAt());
    }

    @Test
    public void transportMigrationCreatesANewVersionWithoutMutatingOriginal() {
        TeamDefinition original = definition();

        TeamDefinition migrated = original.withTransportGroup("team-acp-new", 200L);

        assertEquals("team-acp-instance", original.getTransportGroup());
        assertEquals(1L, original.getVersion());
        assertEquals("team-acp-new", migrated.getTransportGroup());
        assertEquals(2L, migrated.getVersion());
        assertEquals(200L, migrated.getUpdatedAt());
    }

    @Test(expected = IllegalArgumentException.class)
    public void definitionRejectsDuplicateMemberIds() {
        TeamDefinition.creating("team-1", "owner-1", "Team",
                "team-acp-instance", "request-1",
                Arrays.asList(member("member-1", "One"),
                        member("member-1", "Two")), 100L);
    }

    @Test
    public void definitionAcceptsSingleMember() {
        TeamDefinition definition = TeamDefinition.creating(
                "team-1", "owner-1", "Team", "team-acp-instance", "request-1",
                Collections.singletonList(member("member-1", "One")), 100L);

        assertEquals(1, definition.getMembers().size());
        assertEquals("member-1", definition.getMembers().get(0).getTeamMemberId());
    }

    @Test(expected = IllegalStateException.class)
    public void terminalDefinitionCannotTransition() {
        definition()
                .transitionTo(TeamState.DELETED, null, 200L)
                .transitionTo(TeamState.READY, null, 300L);
    }

    @Test
    public void errorAndTombstoneExposeDefensiveCollections() {
        TeamError error = new TeamError(TeamErrorCode.CLIENT_START_FAILED,
                "failed", true, Collections.singletonMap("memberId", "member-1"), 10L);
        TeamTombstone tombstone = new TeamTombstone("team-1", "delete-1",
                TeamState.DELETED_WITH_WARNINGS, 20L, 30L,
                Collections.singletonList(error));

        assertEquals("member-1", error.getDetails().get("memberId"));
        assertEquals(1, tombstone.getWarnings().size());
        assertEquals(TeamState.DELETED_WITH_WARNINGS, tombstone.getFinalState());
    }

    private static TeamDefinition definition() {
        return TeamDefinition.creating("team-1", "owner-1", "Team",
                "team-acp-instance", "request-1",
                Arrays.asList(member("member-1", "One"),
                        member("member-2", "Two")), 100L);
    }

    private static TeamMemberDefinition member(String id, String name) {
        return new TeamMemberDefinition(id, "source-" + id, name,
                name, "remark", "fingerprint-" + id);
    }
}
