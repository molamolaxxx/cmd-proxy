package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamError;
import com.mola.cmd.proxy.app.acp.team.model.TeamErrorCode;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberState;
import com.mola.cmd.proxy.app.acp.team.model.TeamState;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCreateSpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class TeamRecoveryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void readyRestartsThroughRecoveringWithNewClients() throws Exception {
        RecoveryFixture fixture = fixture();
        TeamDefinition creating = definition(fixture.resolver);
        fixture.store.saveTeam(creating);
        fixture.store.saveTeam(creating.transitionWithMembers(
                TeamState.READY, readyMembers(creating), null, 2L));

        fixture.manager.recoverPersistedDefinitions();

        assertTrue(fixture.events.await(2, TimeUnit.SECONDS));
        TeamDefinition recovered = fixture.store.loadTeam("team-1").get();
        assertEquals(TeamState.READY, recovered.getState());
        assertEquals(4L, recovered.getVersion());
        assertEquals(2, fixture.registry.size());
        assertEquals("TEAM_RECOVERY_STARTED",
                fixture.published.get(0).getType().name());
        assertEquals("TEAM_RECOVERED",
                fixture.published.get(1).getType().name());
        fixture.manager.close();
    }

    @Test
    public void creatingResumesStartup() throws Exception {
        RecoveryFixture creatingFixture = fixture();
        creatingFixture.store.saveTeam(
                definition(creatingFixture.resolver));
        creatingFixture.manager.recoverPersistedDefinitions();
        awaitState(creatingFixture.store, TeamState.READY);
        assertEquals(TeamState.READY,
                creatingFixture.store.loadTeam("team-1").get().getState());
        creatingFixture.manager.close();
    }

    @Test
    public void retryableFailedRestartsThroughRecovering() throws Exception {
        RecoveryFixture failedFixture = fixture();
        TeamDefinition creating = definition(failedFixture.resolver);
        TeamError error = TeamError.of(
                TeamErrorCode.CLIENT_START_FAILED, "failed before restart", true);
        failedFixture.store.saveTeam(creating);
        failedFixture.store.saveTeam(creating.transitionWithMembers(
                TeamState.FAILED, creating.getMembers(), error, 2L));
        failedFixture.manager.recoverPersistedDefinitions();

        assertTrue(failedFixture.events.await(2, TimeUnit.SECONDS));
        assertEquals(2, failedFixture.starts.get());
        assertEquals(TeamState.READY,
                failedFixture.manager.getRuntime("team-1").get()
                        .getDefinition().getState());
        assertEquals("TEAM_RECOVERY_STARTED",
                failedFixture.published.get(0).getType().name());
        assertEquals("TEAM_RECOVERED",
                failedFixture.published.get(1).getType().name());
        failedFixture.manager.close();
    }

    @Test
    public void nonRetryableFailedStaysBlocked() throws Exception {
        RecoveryFixture fixture = fixture();
        TeamDefinition creating = definition(fixture.resolver);
        TeamError error = TeamError.of(
                TeamErrorCode.CLIENT_START_FAILED, "failed before restart", false);
        fixture.store.saveTeam(creating);
        fixture.store.saveTeam(creating.transitionWithMembers(
                TeamState.FAILED, creating.getMembers(), error, 2L));

        fixture.manager.recoverPersistedDefinitions();

        Thread.sleep(50L);
        assertEquals(0, fixture.starts.get());
        assertEquals(TeamState.FAILED,
                fixture.manager.getRuntime("team-1").get()
                        .getDefinition().getState());
        assertFalse(fixture.manager.getRuntime("team-1").get()
                .isAcceptingRequests());
        fixture.manager.close();
    }

    @Test
    public void retryableFailedDoesNotLoopWhenRecoveryFailsAgain() throws Exception {
        RecoveryFixture fixture = fixture(true);
        TeamDefinition creating = definition(fixture.resolver);
        TeamError error = TeamError.of(
                TeamErrorCode.CLIENT_START_FAILED, "failed before restart", true);
        fixture.store.saveTeam(creating);
        fixture.store.saveTeam(creating.transitionWithMembers(
                TeamState.FAILED, creating.getMembers(), error, 2L));

        fixture.manager.recoverPersistedDefinitions();

        assertTrue(fixture.events.await(2, TimeUnit.SECONDS));
        assertEquals(TeamState.FAILED,
                fixture.manager.getRuntime("team-1").get()
                        .getDefinition().getState());
        assertEquals("TEAM_RECOVERY_STARTED",
                fixture.published.get(0).getType().name());
        assertEquals("TEAM_RECOVERY_FAILED",
                fixture.published.get(1).getType().name());
        int startsAfterFailure = fixture.starts.get();
        Thread.sleep(100L);
        assertEquals(2, startsAfterFailure);
        assertEquals(startsAfterFailure, fixture.starts.get());
        fixture.manager.close();
    }

    private RecoveryFixture fixture() throws Exception {
        return fixture(false);
    }

    private RecoveryFixture fixture(boolean failStarts) throws Exception {
        TeamStore store = new TeamStore(
                temporaryFolder.newFolder().toPath());
        Map<String, AcpRobotParam> robots = new HashMap<>();
        robots.put("group-1", robot("Robot One"));
        robots.put("group-2", robot("Robot Two"));
        MapTeamSourceRobotResolver resolver =
                new MapTeamSourceRobotResolver(robots);
        TeamClientRegistry registry = new TeamClientRegistry();
        AtomicInteger starts = new AtomicInteger();
        TeamStartupCoordinator coordinator = new TeamStartupCoordinator(
                resolver, (runtime, member, snapshot, options, created) -> {
            starts.incrementAndGet();
            if (failStarts) {
                throw new IOException("member recovery failed");
            }
            AcpClient client = client(
                    runtime.getDefinition(), member, snapshot.copyRobotParam());
            created.accept(client);
            return client;
        }, registry, 2, 1_000L);
        List<TeamEventEnvelope> published =
                java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch events = new CountDownLatch(2);
        TeamManager manager = new TeamManager(
                store, registry, resolver, event -> {
            published.add(event);
            events.countDown();
        }, coordinator);
        return new RecoveryFixture(
                store, resolver, registry, manager,
                published, events, starts);
    }

    private static TeamDefinition definition(
            MapTeamSourceRobotResolver resolver) throws Exception {
        TeamMemberCreateSpec first = new TeamMemberCreateSpec(
                "member-1", "acp-Robot_One", "group-1", 0);
        TeamMemberCreateSpec second = new TeamMemberCreateSpec(
                "member-2", "acp-Robot_Two", "group-2", 1);
        return TeamDefinition.creating(
                "team-1", "owner-1", "Team", "team-acp-instance",
                "create-1", Arrays.asList(
                        member(first, "Robot One", resolver),
                        member(second, "Robot Two", resolver)), 1L);
    }

    private static TeamMemberDefinition member(
            TeamMemberCreateSpec spec, String name,
            MapTeamSourceRobotResolver resolver) throws Exception {
        return new TeamMemberDefinition(
                spec.getTeamMemberId(), spec.getSourceRobotId(),
                spec.getSourceGroupId(), name, name, "", spec.getOrder(),
                "remark", resolver.snapshot(spec).getConfigFingerprint());
    }

    private static List<TeamMemberDefinition> readyMembers(
            TeamDefinition definition) {
        return Arrays.asList(
                definition.getMembers().get(0).withState(
                        TeamMemberState.READY, "old-1", null),
                definition.getMembers().get(1).withState(
                        TeamMemberState.READY, "old-2", null));
    }

    private static AcpClient client(
            TeamDefinition team, TeamMemberDefinition member,
            AcpRobotParam robot) {
        return new AcpClient(".", AcpClientIdentity.team(
                member.getAcpClientId(), team.getTransportGroup(),
                "team/" + team.getTeamId() + "/"
                        + member.getTeamMemberId(),
                team.getOwnerChatterId(), team.getTeamId(),
                member.getTeamMemberId(), member.getSourceRobotName()), robot);
    }

    private static AcpRobotParam robot(String name) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName(name);
        return robot;
    }

    private static void awaitState(TeamStore store, TeamState state)
            throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            if (store.loadTeam("team-1").get().getState() == state) {
                return;
            }
            Thread.sleep(10L);
        }
        fail("Timed out waiting for state " + state);
    }

    private static final class RecoveryFixture {
        private final TeamStore store;
        private final MapTeamSourceRobotResolver resolver;
        private final TeamClientRegistry registry;
        private final TeamManager manager;
        private final List<TeamEventEnvelope> published;
        private final CountDownLatch events;
        private final AtomicInteger starts;

        private RecoveryFixture(
                TeamStore store, MapTeamSourceRobotResolver resolver,
                TeamClientRegistry registry, TeamManager manager,
                List<TeamEventEnvelope> published, CountDownLatch events,
                AtomicInteger starts) {
            this.store = store;
            this.resolver = resolver;
            this.registry = registry;
            this.manager = manager;
            this.published = published;
            this.events = events;
            this.starts = starts;
        }
    }
}
