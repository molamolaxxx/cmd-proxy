package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.schedule.ScheduleTaskManager;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleConfig;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMetricsSnapshot;
import com.mola.cmd.proxy.app.acp.team.model.TeamState;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamCommandResult;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamCreateCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamDeleteCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCreateSpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class TeamScaleAndQuotaTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void concurrentCreatesApplyInstanceQuotaAtomically() throws Exception {
        Fixture fixture = fixture(new TeamLimits(1, 2, 2));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<TeamCommandResult> first = executor.submit(() -> {
            start.await();
            return fixture.manager.create(create("team-a", "create-a"),
                    "team-acp-instance");
        });
        Future<TeamCommandResult> second = executor.submit(() -> {
            start.await();
            return fixture.manager.create(create("team-b", "create-b"),
                    "team-acp-instance");
        });

        start.countDown();
        TeamCommandResult one = first.get(2, TimeUnit.SECONDS);
        TeamCommandResult two = second.get(2, TimeUnit.SECONDS);
        int accepted = ("ACCEPTED".equals(one.getCode()) ? 1 : 0)
                + ("ACCEPTED".equals(two.getCode()) ? 1 : 0);
        int rejected = ("QUOTA_EXCEEDED".equals(one.getCode()) ? 1 : 0)
                + ("QUOTA_EXCEEDED".equals(two.getCode()) ? 1 : 0);

        assertEquals(1, accepted);
        assertEquals(1, rejected);
        assertEquals(1, fixture.manager.snapshotDefinitions().size());
        TeamMetricsSnapshot metrics = fixture.manager.metricsSnapshot();
        assertEquals(1L, metrics.getCreateAccepted());
        assertEquals(1L, metrics.getCreateQuotaRejected());
        assertEquals(1, metrics.getActiveTeams());
        assertEquals(2, metrics.getActiveMembers());
        executor.shutdownNow();
        fixture.manager.close();
    }

    @Test
    public void oneHundredCreateReadyDeleteCyclesReturnRuntimeResourcesToZero()
            throws Exception {
        Fixture fixture = fixture(new TeamLimits(2, 2, 4));
        for (int index = 0; index < 100; index++) {
            String teamId = "team-" + index;
            String createRequest = "create-" + index;
            TeamCommandResult created = fixture.manager.create(
                    create(teamId, createRequest), "team-acp-instance");
            assertEquals("ACCEPTED", created.getCode());
            TeamDefinition ready = awaitState(
                    fixture.manager, teamId, TeamState.READY);

            fixture.manager.getOrCreateTalkToDispatcher(teamId);
            fixture.schedule.createTask(ScheduleOwnerKey.team(
                            "owner-1", teamId, "member-a", "Robot One"),
                    "task-" + index, "prompt",
                    new ScheduleConfig("once", "+1h"));

            TeamCommandResult deleted = fixture.manager.delete(
                    new TeamDeleteCommand("1", "delete-" + index,
                            "owner-1", teamId, ready.getVersion()));
            assertEquals("DELETED", deleted.getCode());
            assertTrue(fixture.manager.resourceSnapshot(teamId).isZero());
        }

        TeamMetricsSnapshot metrics = fixture.manager.metricsSnapshot();
        assertEquals(100L, metrics.getCreateAccepted());
        assertEquals(100L, metrics.getDeleteCompleted());
        assertEquals(0L, metrics.getDeleteWithWarnings());
        assertEquals(0, metrics.getActiveTeams());
        assertEquals(0, metrics.getActiveMembers());
        assertTrue(metrics.getResources().isZero());
        assertEquals(100, fixture.store.loadTombstones().size());
        fixture.manager.close();
    }

    private Fixture fixture(TeamLimits limits) throws Exception {
        Path storeRoot = temporaryFolder.newFolder().toPath();
        Path sessionRoot = temporaryFolder.newFolder().toPath();
        Path archiveRoot = temporaryFolder.newFolder().toPath();
        Path scheduleRoot = temporaryFolder.newFolder().toPath();
        TeamStore store = new TeamStore(storeRoot);
        Map<String, AcpRobotParam> robots = new HashMap<>();
        robots.put("group-a", robot("Robot One"));
        robots.put("group-b", robot("Robot Two"));
        MapTeamSourceRobotResolver resolver =
                new MapTeamSourceRobotResolver(robots);
        TeamClientRegistry registry = new TeamClientRegistry();
        TeamStartupCoordinator coordinator = new TeamStartupCoordinator(
                resolver, (runtime, member, source, options, created) -> {
            AcpClient client = new AcpClient(".", AcpClientIdentity.team(
                    member.getAcpClientId(),
                    runtime.getDefinition().getTransportGroup(),
                    "team/" + runtime.getDefinition().getTeamId()
                            + "/" + member.getTeamMemberId(),
                    runtime.getDefinition().getOwnerChatterId(),
                    runtime.getDefinition().getTeamId(),
                    member.getTeamMemberId(), member.getSourceRobotName()),
                    source.copyRobotParam());
            created.accept(client);
            return client;
        }, registry, 2, 2_000L);
        TeamManager manager = new TeamManager(
                store, registry, resolver, event -> { }, coordinator,
                new TeamHistoryArchiver(
                        sessionRoot.resolve("team"), archiveRoot),
                2_000L, limits);
        ScheduleTaskManager schedule = new ScheduleTaskManager(scheduleRoot);
        manager.setScheduleCleanup(schedule::cleanupTeam);
        manager.setScheduleOrphanCleanup(
                schedule::cleanupOrphanTeams, schedule::teamOwnerCount,
                schedule::teamOwnerCount);
        return new Fixture(store, manager, schedule);
    }

    private static TeamDefinition awaitState(
            TeamManager manager, String teamId, TeamState state)
            throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            TeamDefinition definition = manager.getRuntime(teamId)
                    .map(runtime -> runtime.getDefinition()).orElse(null);
            if (definition != null && definition.getState() == state) {
                return definition;
            }
            Thread.sleep(2L);
        }
        fail("Team did not reach " + state + ": " + teamId);
        return null;
    }

    private static TeamCreateCommand create(String teamId, String requestId) {
        return new TeamCreateCommand("1", requestId, teamId,
                "owner-1", "Team " + teamId, Arrays.asList(
                new TeamMemberCreateSpec(
                        "member-a", "acp-Robot_One", "group-a", 0),
                new TeamMemberCreateSpec(
                        "member-b", "acp-Robot_Two", "group-b", 1)));
    }

    private static AcpRobotParam robot(String name) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName(name);
        robot.setSignature("Handles " + name);
        return robot;
    }

    private static final class Fixture {
        private final TeamStore store;
        private final TeamManager manager;
        private final ScheduleTaskManager schedule;

        private Fixture(TeamStore store, TeamManager manager,
                        ScheduleTaskManager schedule) {
            this.store = store;
            this.manager = manager;
            this.schedule = schedule;
        }
    }
}
