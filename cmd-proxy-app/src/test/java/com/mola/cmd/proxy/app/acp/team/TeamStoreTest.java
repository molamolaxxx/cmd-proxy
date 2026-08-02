package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamOperationRecord;
import com.mola.cmd.proxy.app.acp.team.model.TeamState;
import com.mola.cmd.proxy.app.acp.team.model.TeamTombstone;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class TeamStoreTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void savesAndLoadsTeamWithAtomicFileLayout() throws Exception {
        TeamStore store = store();
        store.saveTeam(definition("team-1"));

        TeamDefinition loaded = store.loadTeam("team-1").get();
        assertEquals("team-1", loaded.getTeamId());
        assertEquals(1L, loaded.getVersion());
        assertTrue(Files.isRegularFile(
                store.getTeamsRoot().resolve("team-1").resolve("team.json")));
        assertEquals(0L, Files.walk(store.getTeamsRoot())
                .filter(path -> path.getFileName().toString().contains(".tmp-"))
                .count());
    }

    @Test
    public void updateRequiresExactlyNextVersion() throws Exception {
        TeamStore store = store();
        TeamDefinition initial = definition("team-1");
        store.saveTeam(initial);
        store.saveTeam(initial.transitionTo(TeamState.READY, null, 200L));

        try {
            store.saveTeam(initial);
            fail("expected version conflict");
        } catch (TeamStore.VersionConflictException e) {
            assertEquals(2L, e.getPersistedVersion());
            assertEquals(1L, e.getAttemptedVersion());
        }
    }

    @Test
    public void persistsOperationAndTombstoneInDedicatedNamespaces() throws Exception {
        TeamStore store = store();
        TeamOperationRecord operation = new TeamOperationRecord(
                "request-1", TeamOperationRecord.Operation.CREATE, "sha256",
                "team-1", TeamOperationRecord.Status.ACCEPTED, null, 10L, 20L);
        TeamTombstone tombstone = new TeamTombstone(
                "team-1", "delete-1", TeamState.DELETED,
                30L, 40L, Collections.emptyList());

        store.saveOperation(operation);
        store.saveTombstone(tombstone);

        assertEquals("team-1", store.loadOperation("request-1").get().getTeamId());
        assertEquals(TeamState.DELETED,
                store.loadTombstone("team-1").get().getFinalState());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPathTraversalIdentifiers() throws Exception {
        store().loadTeam("../outside");
    }

    @Test
    public void loadsTeamsInStableOrder() throws Exception {
        TeamStore store = store();
        store.saveTeam(definition("team-b"));
        store.saveTeam(definition("team-a"));

        assertEquals("team-a", store.loadTeams().get(0).getTeamId());
        assertEquals("team-b", store.loadTeams().get(1).getTeamId());
    }

    private TeamStore store() throws Exception {
        return new TeamStore(temporaryFolder.newFolder("teams").toPath());
    }

    private static TeamDefinition definition(String teamId) {
        return TeamDefinition.creating(teamId, "owner-1", "Team",
                "team-acp-instance", "request-" + teamId,
                Arrays.asList(member("member-1"), member("member-2")), 100L);
    }

    private static TeamMemberDefinition member(String id) {
        return new TeamMemberDefinition(id, "source-" + id, "Robot " + id,
                "Robot " + id, "remark", "fingerprint-" + id);
    }
}
