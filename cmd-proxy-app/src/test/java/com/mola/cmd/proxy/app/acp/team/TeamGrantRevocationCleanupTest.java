package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventType;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberState;
import com.mola.cmd.proxy.app.acp.team.model.TeamState;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamCreateCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCreateSpec;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamRosterMemberSpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class TeamGrantRevocationCleanupTest {

    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void revokeWaitsForBusyTurnThenUsesDeleteStateMachine() throws Exception {
        TeamStore store = new TeamStore(temporaryFolder.newFolder().toPath());
        String sharedGroup = TeamSharedSourceIds.groupId(
                "instance-b", "acp-Remote_Robot");
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("Remote Robot");
        robot.setTeamSharedWithChatterIds(Collections.singletonList("owner-1"));
        Map<String, AcpRobotParam> sources = new HashMap<>();
        sources.put(sharedGroup, robot);
        TeamClientRegistry registry = new TeamClientRegistry();
        List<TeamEventEnvelope> events =
                Collections.synchronizedList(new ArrayList<>());
        TeamManager manager = new TeamManager(store, registry,
                new MapTeamSourceRobotResolver(sources), events::add);
        TeamCreateCommand create = new TeamCreateCommand(
                "1", "create-1", "team-1", "owner-1", "Mixed",
                Collections.singletonList(new TeamMemberCreateSpec(
                        "remote-1", "acp-Remote_Robot", sharedGroup, 1)),
                true, java.util.Arrays.asList(
                        new TeamRosterMemberSpec("home-1", "team-acp-home-1",
                                "Home", "home", 0),
                        new TeamRosterMemberSpec("remote-1", "team-acp-remote-1",
                                "Remote", "remote", 1)));
        assertTrue(manager.create(create, "team-acp-instance-b").isAccepted());

        com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime runtime =
                manager.getRuntime("team-1").get();
        TeamMemberDefinition busyMember = runtime.getDefinition().getMembers().get(0)
                .withState(TeamMemberState.BUSY, "session-1", null);
        TeamDefinition ready = runtime.getDefinition().transitionWithMembers(
                TeamState.READY, Collections.singletonList(busyMember), null,
                System.currentTimeMillis());
        store.saveTeam(ready);
        assertTrue(runtime.publishNextDefinition(ready));
        TestClient client = new TestClient(busyMember, ready);
        client.setClientState(AbstractAcpClient.State.BUSY);
        assertTrue(registry.register("team-1", "remote-1", client));

        robot.setTeamSharedWithChatterIds(Collections.emptyList());
        manager.reconcileRevokedGrants();

        assertTrue(manager.isRevokedGrantCleanupPending("team-1"));
        assertEquals(TeamState.READY, runtime.getDefinition().getState());
        assertEquals(0, client.closeCount.get());
        assertTrue(events.stream().anyMatch(event ->
                event.getType() == TeamEventType.TEAM_STATE_CHANGED));

        client.setClientState(AbstractAcpClient.State.READY);
        manager.onMemberState("team-1", "remote-1", TeamMemberState.READY, null);
        await(() -> events.stream().anyMatch(event ->
                event.getType() == TeamEventType.TEAM_DELETE_ACCEPTED));
        await(() -> !manager.isRevokedGrantCleanupPending("team-1"));

        assertTrue(client.closeCount.get() > 0);
        assertTrue(events.stream().anyMatch(event ->
                event.getType() == TeamEventType.TEAM_DELETED));
        manager.close();
    }

    private static void await(Check check) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) return;
            Thread.sleep(10L);
        }
        fail("timed out waiting for revoked grant cleanup");
    }

    private interface Check { boolean ok(); }

    private static final class TestClient extends AcpClient {
        private final AtomicInteger closeCount = new AtomicInteger();

        private TestClient(TeamMemberDefinition member, TeamDefinition team) {
            super(".", AcpClientIdentity.team(member.getAcpClientId(),
                    team.getTransportGroup(), "team/team-1/remote-1", "owner-1",
                    "team-1", "remote-1", member.getSourceRobotName()), robot());
            setSessionId("session-1");
        }

        private void setClientState(AbstractAcpClient.State next) { state.set(next); }

        @Override public void close() throws IOException {
            closeCount.incrementAndGet();
            state.set(AbstractAcpClient.State.CLOSED);
        }

        private static AcpRobotParam robot() {
            AcpRobotParam value = new AcpRobotParam();
            value.setName("Remote Robot");
            return value;
        }
    }
}
