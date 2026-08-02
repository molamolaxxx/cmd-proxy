package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventSink;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventType;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberState;
import com.mola.cmd.proxy.app.acp.team.model.TeamState;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class TeamLifecycleStartupTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void createTransitionsToReadyAndPreservesOriginalIdempotentResult()
            throws Exception {
        Fixture fixture = fixture(false);
        String payload = createJson();

        Map<String, String> accepted =
                fixture.handler.handleCreate("rpc-1", new String[]{payload});
        assertTrue(fixture.events.await(2, TimeUnit.SECONDS));
        Map<String, String> replay =
                fixture.handler.handleCreate("rpc-2", new String[]{payload});

        TeamDefinition ready = fixture.store.loadTeam("team-1").get();
        assertEquals(TeamState.READY, ready.getState());
        assertEquals(2L, ready.getVersion());
        assertEquals(2, fixture.registry.size());
        assertTrue(ready.getMembers().stream().allMatch(
                member -> member.getState() == TeamMemberState.READY));
        assertEquals("ACCEPTED", replay.get("code"));
        assertEquals(accepted.get("data"), replay.get("data"));
        assertEquals(TeamEventType.TEAM_CREATE_ACCEPTED, fixture.published.get(0).getType());
        assertEquals(TeamEventType.TEAM_READY, fixture.published.get(1).getType());
        assertEquals(2L, fixture.published.get(1).getTeamVersion());
        fixture.manager.close();
    }

    @Test
    public void oneMemberFailurePersistsFailedStateAndEmitsStructuredEvent()
            throws Exception {
        Fixture fixture = fixture(true);

        Map<String, String> accepted =
                fixture.handler.handleCreate("rpc-1", new String[]{createJson()});
        assertEquals("ACCEPTED", accepted.get("code"));
        assertTrue(fixture.events.await(2, TimeUnit.SECONDS));

        TeamDefinition failed = fixture.store.loadTeam("team-1").get();
        assertEquals(TeamState.FAILED, failed.getState());
        assertEquals(0, fixture.registry.size());
        assertNotNull(failed.getLastError());
        assertTrue(failed.getMembers().stream().anyMatch(
                member -> member.getState() == TeamMemberState.ERROR));
        TeamEventEnvelope event = fixture.published.get(1);
        assertEquals(TeamEventType.TEAM_CREATE_FAILED, event.getType());
        assertEquals(2L, event.getTeamVersion());
        assertTrue(event.getData() instanceof Map);
        assertTrue(((Map<?, ?>) event.getData()).containsKey("error"));
        fixture.manager.close();
    }

    private Fixture fixture(boolean failSecond) throws Exception {
        TeamStore store = new TeamStore(temporaryFolder.newFolder("teams").toPath());
        Map<String, AcpRobotParam> robots = new HashMap<>();
        robots.put("group-1", robot("Robot One"));
        robots.put("group-2", robot("Robot Two"));
        MapTeamSourceRobotResolver resolver = new MapTeamSourceRobotResolver(robots);
        TeamClientRegistry registry = new TeamClientRegistry();
        List<TeamEventEnvelope> published =
                java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch events = new CountDownLatch(2);
        TeamEventSink sink = event -> {
            published.add(event);
            events.countDown();
        };
        TeamStartupCoordinator coordinator = new TeamStartupCoordinator(
                resolver, (runtime, member, snapshot, options, created) -> {
            if (failSecond && "member-2".equals(member.getTeamMemberId())) {
                throw new IllegalStateException("member-2 failed");
            }
            AcpClient client = client(
                    runtime.getDefinition(), member, snapshot.copyRobotParam());
            created.accept(client);
            return client;
        }, registry, 2, 1_000L);
        TeamManager manager = new TeamManager(
                store, registry, resolver, sink, coordinator);
        return new Fixture(store, registry, manager,
                new TeamCommandHandler(manager, "team-acp-instance"), published, events);
    }

    private static AcpClient client(TeamDefinition team, TeamMemberDefinition member,
                                    AcpRobotParam robot) {
        return new AcpClient(".", AcpClientIdentity.team(
                member.getAcpClientId(), team.getTransportGroup(),
                "team/" + team.getTeamId() + "/" + member.getTeamMemberId(),
                team.getOwnerChatterId(), team.getTeamId(),
                member.getTeamMemberId(), member.getSourceRobotName()), robot);
    }

    private static AcpRobotParam robot(String name) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName(name);
        robot.setSignature("Handles " + name);
        return robot;
    }

    private static String createJson() {
        return "{\"schemaVersion\":\"1\",\"requestId\":\"request-1\","
                + "\"teamId\":\"team-1\",\"ownerChatterId\":\"owner-1\","
                + "\"name\":\"Team\",\"members\":["
                + "{\"teamMemberId\":\"member-1\",\"sourceRobotId\":\"acp-Robot_One\","
                + "\"sourceGroupId\":\"group-1\",\"order\":0},"
                + "{\"teamMemberId\":\"member-2\",\"sourceRobotId\":\"acp-Robot_Two\","
                + "\"sourceGroupId\":\"group-2\",\"order\":1}]}";
    }

    private static final class Fixture {
        private final TeamStore store;
        private final TeamClientRegistry registry;
        private final TeamManager manager;
        private final TeamCommandHandler handler;
        private final List<TeamEventEnvelope> published;
        private final CountDownLatch events;

        private Fixture(TeamStore store, TeamClientRegistry registry,
                        TeamManager manager, TeamCommandHandler handler,
                        List<TeamEventEnvelope> published, CountDownLatch events) {
            this.store = store;
            this.registry = registry;
            this.manager = manager;
            this.handler = handler;
            this.published = published;
            this.events = events;
        }
    }
}
