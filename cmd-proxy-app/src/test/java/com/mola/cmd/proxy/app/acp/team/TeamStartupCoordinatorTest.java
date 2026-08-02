package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberState;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCreateSpec;
import com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class TeamStartupCoordinatorTest {

    @Test
    public void startsAllMembersAndRegistersOnlyAfterEveryStartSucceeds() throws Exception {
        Fixture fixture = fixture((runtime, member, snapshot, options, created) -> {
            AcpClient client = client(
                    runtime.getDefinition(), member, snapshot.copyRobotParam());
            created.accept(client);
            return client;
        });

        TeamStartupCoordinator.Result result = fixture.coordinator
                .startAsync(fixture.runtime).get(2, TimeUnit.SECONDS);

        assertTrue(result.isSuccessful());
        assertEquals(2, fixture.registry.size());
        assertEquals(TeamMemberState.READY, result.getMembers().get(0).getState());
        fixture.coordinator.close();
    }

    @Test
    public void oneFailureRollsBackEveryStartedClient() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        Fixture fixture = fixture((runtime, member, snapshot, options, created) -> {
            if (starts.incrementAndGet() == 2) {
                throw new IllegalStateException("boom");
            }
            AcpClient client = client(
                    runtime.getDefinition(), member, snapshot.copyRobotParam());
            created.accept(client);
            return client;
        });

        TeamStartupCoordinator.Result result = fixture.coordinator
                .startAsync(fixture.runtime).get(2, TimeUnit.SECONDS);

        assertFalse(result.isSuccessful());
        assertEquals(0, fixture.registry.size());
        assertNotNull(result.getError());
        assertTrue(result.getMembers().stream().anyMatch(
                member -> member.getState() == TeamMemberState.ERROR));
        fixture.coordinator.close();
    }

    @Test
    public void timeoutFailsWholeTeamWithoutRegisteringPartialClients() throws Exception {
        Fixture fixture = new Fixture(25L, (runtime, member, snapshot, options, created) -> {
            AcpClient client = client(
                    runtime.getDefinition(), member, snapshot.copyRobotParam());
            created.accept(client);
            Thread.sleep(250L);
            return client;
        });

        TeamStartupCoordinator.Result result = fixture.coordinator
                .startAsync(fixture.runtime).get(2, TimeUnit.SECONDS);

        assertFalse(result.isSuccessful());
        assertEquals(0, fixture.registry.size());
        assertTrue(result.getError().getMessage().contains("timed out"));
        fixture.coordinator.close();
    }

    private static Fixture fixture(TeamMemberClientStarter starter) {
        return new Fixture(1_000L, starter);
    }

    private static AcpClient client(TeamDefinition team, TeamMemberDefinition member,
                                    AcpRobotParam robot) {
        AcpClientIdentity identity = AcpClientIdentity.team(
                member.getAcpClientId(), team.getTransportGroup(),
                "team/" + team.getTeamId() + "/" + member.getTeamMemberId(),
                team.getOwnerChatterId(), team.getTeamId(),
                member.getTeamMemberId(), member.getSourceRobotName());
        return new AcpClient(".", identity, robot);
    }

    private static final class Fixture {
        private final TeamClientRegistry registry = new TeamClientRegistry();
        private final TeamRuntime runtime;
        private final TeamStartupCoordinator coordinator;

        private Fixture(long timeoutMillis, TeamMemberClientStarter starter) {
            Map<String, AcpRobotParam> robots = new HashMap<>();
            robots.put("group-1", robot("Robot One"));
            robots.put("group-2", robot("Robot Two"));
            MapTeamSourceRobotResolver resolver = new MapTeamSourceRobotResolver(robots);
            TeamMemberDefinition first = member(
                    resolver, "member-1", "group-1", "Robot One", 0);
            TeamMemberDefinition second = member(
                    resolver, "member-2", "group-2", "Robot Two", 1);
            runtime = new TeamRuntime(TeamDefinition.creating(
                    "team-1", "owner-1", "Team", "team-acp-instance",
                    "request-1", Arrays.asList(first, second), 1L));
            coordinator = new TeamStartupCoordinator(
                    resolver, starter, registry, 2, timeoutMillis);
        }
    }

    private static TeamMemberDefinition member(MapTeamSourceRobotResolver resolver,
                                               String memberId, String groupId,
                                               String robotName, int order) {
        try {
            String robotId = "acp-" + robotName.replace(" ", "_");
            TeamMemberCreateSpec spec = new TeamMemberCreateSpec(
                    memberId, robotId, groupId, order);
            return new TeamMemberDefinition(memberId, robotId, groupId, robotName,
                    robotName, "", order, "remark",
                    resolver.snapshot(spec).getConfigFingerprint());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static AcpRobotParam robot(String name) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName(name);
        return robot;
    }
}
