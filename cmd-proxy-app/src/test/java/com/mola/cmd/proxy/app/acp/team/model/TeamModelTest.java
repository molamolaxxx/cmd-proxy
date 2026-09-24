package com.mola.cmd.proxy.app.acp.team.model;

import org.junit.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    @Test
    public void definitionAcceptsTenMembersAndRejectsEleven() {
        List<TeamMemberDefinition> members = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            members.add(member("member-" + i, "Member " + i));
        }
        TeamDefinition definition = TeamDefinition.creating(
                "team-10", "owner-1", "Ten", "team-acp-instance", "request-10",
                members, 100L);
        assertEquals(10, definition.getMembers().size());

        members.add(member("member-11", "Member 11"));
        try {
            TeamDefinition.creating("team-11", "owner-1", "Eleven",
                    "team-acp-instance", "request-11", members, 100L);
            fail("eleven members must exceed the Team limit");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("between 1 and 10"));
        }
    }

    @Test
    public void mixedDefinitionPersistsGlobalRosterBeyondLocalFragment() {
        TeamMemberDefinition local = member("member-local", "Local");
        TeamDefinition definition = TeamDefinition.creating(
                "team-1", "owner-1", "Mixed", "team-acp-instance", "request-1",
                Collections.singletonList(local), true,
                Arrays.asList(TeamContactRef.from(local),
                        new TeamContactRef("member-remote", "team-acp-member-remote",
                                "Remote", "remote", 1)), 100L);

        assertTrue(definition.isMixedPlacement());
        assertEquals(1, definition.getMembers().size());
        assertEquals(2, definition.getRoster().size());
    }

    @Test
    public void captainTopologyUsesGlobalRosterAndSurvivesTransitions() {
        TeamMemberDefinition local = member("member-local", "Local");
        TeamDefinition captain = TeamDefinition.creating(
                "team-1", "owner-1", "Captain", "team-acp-instance", "request-1",
                Collections.singletonList(local), true,
                Arrays.asList(TeamContactRef.from(local),
                        new TeamContactRef("member-captain", "team-acp-member-captain",
                                "Captain", "coordinates", 1)),
                TeamMode.CAPTAIN, "member-captain", 100L);

        TeamDefinition ready = captain.transitionTo(TeamState.READY, null, 200L);

        assertTrue(ready.isCaptainMode());
        assertEquals("member-captain", ready.getCaptainTeamMemberId());
        assertTrue(ready.canCommunicate("member-local", "member-captain"));
        assertFalse(ready.canCommunicate("member-local", "another-member"));
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
