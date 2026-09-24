package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberState;
import com.mola.cmd.proxy.app.acp.team.model.TeamMode;
import com.mola.cmd.proxy.app.acp.team.model.TeamState;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamCommandResult;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCreateSpec;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMembersUpdateCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCommand;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class TeamMembersUpdateTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void changesRosterAndRenewsEveryRemainingMemberSession() throws Exception {
        Fixture fixture = fixture(false);
        TeamMembersUpdateCommand command = update("change-1", 2L,
                spec("member-a", "group-a", 0), spec("member-c", "group-c", 1));

        Map<String, String> rpcResult = new TeamCommandHandler(fixture.manager,
                "team-acp-instance").handleUpdateMembers("rpc-change-1",
                new String[]{new com.google.gson.Gson().toJson(command)});
        assertEquals("true", rpcResult.get("accepted"));
        assertEquals("UPDATED", rpcResult.get("code"));
        TeamCommandResult result = fixture.manager.updateMembers(command);
        TeamCommandResult repeated = fixture.manager.updateMembers(command);
        TeamDefinition team = fixture.manager.getRuntime("team-1").get().getDefinition();

        assertTrue(result.isAccepted());
        assertEquals("UPDATED", result.getCode());
        assertEquals(result.getTeamVersion(), repeated.getTeamVersion());
        assertEquals(2, team.getMembers().size());
        assertEquals("member-c", team.getRoster().get(1).getTargetTeamMemberId());
        assertFalse(fixture.registry.get("team-1", "member-b").isPresent());
        assertEquals("new-session-1", fixture.registry.get("team-1", "member-a")
                .get().getSessionId());
        assertEquals("new-session-2", fixture.registry.get("team-1", "member-c")
                .get().getSessionId());
        assertEquals(team.getVersion(), fixture.store.loadTeam("team-1")
                .get().getVersion());
        fixture.close();
    }

    @Test
    public void captainCannotBeRemoved() throws Exception {
        Fixture fixture = fixture(true);

        TeamCommandResult result = fixture.manager.updateMembers(update("change-2", 2L,
                spec("member-b", "group-b", 0), spec("member-c", "group-c", 1)));

        assertFalse(result.isAccepted());
        assertEquals("VALIDATION_ERROR", result.getCode());
        assertEquals(2L, fixture.store.loadTeam("team-1").get().getVersion());
        fixture.close();
    }

    @Test
    public void staleVersionAndBusyMemberDoNotChangeRoster() throws Exception {
        Fixture fixture = fixture(false);
        TeamCommandResult stale = fixture.manager.updateMembers(update("change-3", 1L,
                spec("member-a", "group-a", 0)));
        assertEquals("VERSION_CONFLICT", stale.getCode());
        ((ReadyClient) fixture.registry.get("team-1", "member-a").get()).setBusy();
        TeamCommandResult busy = fixture.manager.updateMembers(update("change-4", 2L,
                spec("member-a", "group-a", 0)));
        assertEquals("MEMBER_BUSY", busy.getCode());
        assertEquals(2L, fixture.store.loadTeam("team-1").get().getVersion());
        fixture.close();
    }

    @Test
    public void failedNewMemberCanRetryWithoutChangingRosterAgain() throws Exception {
        Fixture fixture = fixture(false, true);
        TeamCommandResult result = fixture.manager.updateMembers(update("change-fail", 2L,
                spec("member-a", "group-a", 0), spec("member-c", "group-c", 1)));
        assertEquals("PARTIAL", result.getCode());
        assertEquals(TeamMemberState.ERROR, fixture.manager.getRuntime("team-1")
                .get().getDefinition().getMembers().get(1).getState());

        TeamMemberCommand retry = new com.google.gson.Gson().fromJson(
                "{\"schemaVersion\":\"1\",\"ownerChatterId\":\"owner-1\","
                        + "\"teamId\":\"team-1\",\"teamMemberId\":\"member-c\"}",
                TeamMemberCommand.class);
        assertTrue(fixture.manager.newSession("retry-c", retry).isAccepted());
        assertEquals(TeamMemberState.READY, fixture.manager.getRuntime("team-1")
                .get().getDefinition().getMembers().get(1).getState());
        fixture.close();
    }

    private Fixture fixture(boolean captain) throws Exception {
        return fixture(captain, false);
    }

    private Fixture fixture(boolean captain, boolean failMemberCOnce) throws Exception {
        TeamStore store = new TeamStore(temporaryFolder.newFolder().toPath());
        Map<String, AcpRobotParam> robots = new HashMap<>();
        for (String name : Arrays.asList("a", "b", "c")) {
            AcpRobotParam robot = new AcpRobotParam();
            robot.setName("Robot " + name);
            robots.put("group-" + name, robot);
        }
        MapTeamSourceRobotResolver resolver = new MapTeamSourceRobotResolver(robots);
        List<TeamMemberDefinition> members = Arrays.asList(
                resolved(resolver, spec("member-a", "group-a", 0)),
                resolved(resolver, spec("member-b", "group-b", 1)));
        TeamDefinition creating = TeamDefinition.creating("team-1", "owner-1", "Team",
                "team-acp-instance", "create-1", members, false, null,
                captain ? TeamMode.CAPTAIN : TeamMode.NORMAL,
                captain ? "member-a" : null, 1L);
        TeamDefinition ready = creating.transitionWithMembers(TeamState.READY,
                Arrays.asList(members.get(0).withState(TeamMemberState.READY, "old-a", null),
                        members.get(1).withState(TeamMemberState.READY, "old-b", null)),
                null, 2L);
        store.saveTeam(creating);
        store.saveTeam(ready);
        TeamClientRegistry registry = new TeamClientRegistry();
        registry.register("team-1", "member-a", client(ready, members.get(0), "old-a"));
        registry.register("team-1", "member-b", client(ready, members.get(1), "old-b"));
        AtomicInteger sessions = new AtomicInteger();
        AtomicBoolean failC = new AtomicBoolean(failMemberCOnce);
        TeamStartupCoordinator coordinator = new TeamStartupCoordinator(resolver,
                (runtime, member, snapshot, options, created) -> {
                    if ("member-c".equals(member.getTeamMemberId())
                            && failC.compareAndSet(true, false)) {
                        throw new IOException("temporary startup failure");
                    }
                    ReadyClient next = client(runtime.getDefinition(), member,
                            "new-session-" + sessions.incrementAndGet());
                    created.accept(next);
                    return next;
                }, registry, 2, 1000L);
        TeamManager manager = new TeamManager(store, registry, resolver,
                event -> { }, coordinator);
        assertTrue(manager.attachPersistedDefinition(ready));
        return new Fixture(store, registry, manager, coordinator);
    }

    private static TeamMemberDefinition resolved(MapTeamSourceRobotResolver resolver,
                                                 TeamMemberCreateSpec spec) throws Exception {
        TeamSourceRobotSnapshot snapshot = resolver.snapshot(spec);
        return new TeamMemberDefinition(spec.getTeamMemberId(), spec.getSourceRobotId(),
                spec.getSourceGroupId(), snapshot.copyRobotParam().getName(),
                snapshot.copyRobotParam().getName(), "", spec.getOrder(), "",
                snapshot.getConfigFingerprint());
    }

    private static TeamMemberCreateSpec spec(String id, String group, int order) {
        return new TeamMemberCreateSpec(id, "acp-Robot_" + group.substring(6),
                group, order, "role-" + id);
    }

    private static TeamMembersUpdateCommand update(String requestId, long version,
                                                   TeamMemberCreateSpec... members) {
        return new TeamMembersUpdateCommand("1", requestId, "owner-1", "team-1",
                version, Arrays.asList(members));
    }

    private static ReadyClient client(TeamDefinition team, TeamMemberDefinition member,
                                      String sessionId) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName(member.getSourceRobotName());
        AcpClientIdentity identity = AcpClientIdentity.team(member.getAcpClientId(),
                team.getTransportGroup(), "team/team-1/" + member.getTeamMemberId(),
                team.getOwnerChatterId(), team.getTeamId(), member.getTeamMemberId(),
                member.getSourceRobotName());
        return new ReadyClient(identity, robot, sessionId);
    }

    private static final class ReadyClient extends AcpClient {
        private ReadyClient(AcpClientIdentity identity, AcpRobotParam robot,
                            String sessionId) {
            super(".", identity, robot);
            state.set(State.READY);
            setSessionId(sessionId);
        }

        @Override public void close() throws IOException { state.set(State.CLOSED); }

        private void setBusy() { state.set(AbstractAcpClient.State.BUSY); }
    }

    private static final class Fixture {
        private final TeamStore store;
        private final TeamClientRegistry registry;
        private final TeamManager manager;
        private final TeamStartupCoordinator coordinator;

        private Fixture(TeamStore store, TeamClientRegistry registry,
                        TeamManager manager, TeamStartupCoordinator coordinator) {
            this.store = store;
            this.registry = registry;
            this.manager = manager;
            this.coordinator = coordinator;
        }

        private void close() { manager.close(); coordinator.close(); }
    }
}
