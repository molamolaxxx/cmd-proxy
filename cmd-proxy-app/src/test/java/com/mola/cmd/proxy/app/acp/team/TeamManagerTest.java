package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToRequest;
import com.mola.cmd.proxy.app.acp.team.talkto.TeamTalkToDispatcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;

import static org.junit.Assert.*;

public class TeamManagerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void recoversPersistedDefinitionsWithoutStartingClients() throws Exception {
        TeamStore store = store();
        store.saveTeam(definition("team-b"));
        store.saveTeam(definition("team-a"));
        TeamClientRegistry registry = new TeamClientRegistry();
        TeamManager manager = new TeamManager(store, registry);

        assertEquals(2, manager.recoverPersistedDefinitions());
        assertEquals(0, manager.recoverPersistedDefinitions());
        assertEquals("team-a", manager.snapshotDefinitions().get(0).getTeamId());
        assertEquals(0, registry.size());
    }

    @Test
    public void recoveryMigratesPersistedTransportGroupBeforePublishingRuntime()
            throws Exception {
        TeamStore store = store();
        store.saveTeam(definition("team-1"));
        TeamManager manager = new TeamManager(store, new TeamClientRegistry());

        assertEquals(1, manager.recoverPersistedDefinitions("team-acp-new-instance"));

        TeamDefinition runtime = manager.getRuntime("team-1").get().getDefinition();
        TeamDefinition persisted = store.loadTeam("team-1").get();
        assertEquals("team-acp-new-instance", runtime.getTransportGroup());
        assertEquals("team-acp-new-instance", persisted.getTransportGroup());
        assertEquals(2L, persisted.getVersion());
    }

    @Test
    public void registryRequiresMatchingTeamIdentityAndIsIsolatedFromMainRegistry() {
        TeamClientRegistry registry = new TeamClientRegistry();
        AcpClient client = teamClient("team-1", "member-1");

        assertTrue(registry.register("team-1", "member-1", client));
        assertFalse(registry.register("team-1", "member-1", client));
        assertSame(client, registry.get("team-1", "member-1").get());
        assertEquals(1, registry.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void registryRejectsMainClientIdentity() {
        AcpClient main = new AcpClient(".", "group-main", new AcpRobotParam());
        new TeamClientRegistry().register("team-1", "member-1", main);
    }

    @Test
    public void closeIsIdempotentAndClosesOnlyTeamClients() {
        TeamClientRegistry registry = new TeamClientRegistry();
        AcpClient first = teamClient("team-1", "member-1");
        AcpClient second = teamClient("team-2", "member-2");
        registry.register("team-1", "member-1", first);
        registry.register("team-2", "member-2", second);
        TeamManager manager = new TeamManager(unusedStore(), registry);

        manager.close();
        manager.close();

        assertTrue(manager.isClosed());
        assertEquals(0, registry.size());
        assertEquals(AbstractAcpClient.State.CLOSED, first.getState());
        assertEquals(AbstractAcpClient.State.CLOSED, second.getState());
    }

    @Test
    public void closeAlsoClearsTeamTalkToRuntime() throws Exception {
        TeamStore store = store();
        store.saveTeam(definition("team-1"));
        TeamManager manager = new TeamManager(store, new TeamClientRegistry());
        manager.recoverPersistedDefinitions();
        TeamTalkToDispatcher dispatcher =
                manager.getOrCreateTalkToDispatcher("team-1");

        manager.close();

        assertEquals(0, dispatcher.inboxSize("member-2"));
        assertEquals(0, dispatcher.dedupSize());
        assertTrue(dispatcher.deliver(new TalkToRequest(
                "member-2", "after close", 0), "member-1", "", null)
                .contains("not accepting"));
    }

    private TeamStore store() throws Exception {
        return new TeamStore(temporaryFolder.newFolder("teams").toPath());
    }

    private TeamStore unusedStore() {
        return new TeamStore(temporaryFolder.getRoot().toPath().resolve("unused"));
    }

    private static AcpClient teamClient(String teamId, String memberId) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("Robot");
        AcpClientIdentity identity = AcpClientIdentity.team(
                "team-acp-" + memberId, "team-acp-instance",
                "team/" + teamId + "/" + memberId, "owner-1",
                teamId, memberId, "Robot");
        return new AcpClient(".", identity, robot);
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
