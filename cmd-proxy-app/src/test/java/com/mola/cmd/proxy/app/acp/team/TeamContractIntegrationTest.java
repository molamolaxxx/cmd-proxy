package com.mola.cmd.proxy.app.acp.team;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventType;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamState;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamTransportDescriptor;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamTransportProtocol;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class TeamContractIntegrationTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void discoveryCreateListGetDeleteUseMolaChatStringContract()
            throws Exception {
        Path storeRoot = temporaryFolder.newFolder().toPath();
        Path sessionRoot = temporaryFolder.newFolder().toPath();
        Path archiveRoot = temporaryFolder.newFolder().toPath();
        Map<String, AcpRobotParam> robots = new HashMap<>();
        robots.put("group-a", robot("Robot One"));
        robots.put("group-b", robot("Robot Two"));
        MapTeamSourceRobotResolver resolver =
                new MapTeamSourceRobotResolver(robots);
        TeamClientRegistry registry = new TeamClientRegistry();
        List<TeamEventEnvelope> events =
                java.util.Collections.synchronizedList(new ArrayList<>());
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
                new TeamStore(storeRoot), registry, resolver, events::add,
                coordinator, new TeamHistoryArchiver(
                        sessionRoot.resolve("team"), archiveRoot),
                2_000L, new TeamLimits(5, 6, 20));
        TeamCommandHandler handler =
                new TeamCommandHandler(manager, "team-acp-instance");

        TeamTransportDescriptor descriptor =
                TeamTransportDescriptor.readyForBusiness("instance");
        Map<String, String> described =
                TeamTransportProtocol.describeResult("describe-1", descriptor);
        assertStringMap(described);
        assertTrue(JsonParser.parseString(described.get("data"))
                .getAsJsonObject().get("businessCommandsReady").getAsBoolean());

        Map<String, String> create = handler.handleCreate(
                "rpc-create", one(createJson()));
        assertStringMap(create);
        assertEquals("ACCEPTED", create.get("code"));
        TeamDefinition ready = awaitReady(manager);

        Map<String, String> list = handler.handleList(
                "rpc-list", one(queryJson(false)));
        Map<String, String> get = handler.handleGet(
                "rpc-get", one(queryJson(true)));
        assertStringMap(list);
        assertStringMap(get);
        assertEquals(1, JsonParser.parseString(list.get("data"))
                .getAsJsonObject().getAsJsonArray("teams").size());
        assertEquals("team-1", JsonParser.parseString(get.get("data"))
                .getAsJsonObject().getAsJsonObject("team")
                .get("teamId").getAsString());

        Map<String, String> delete = handler.handleDelete(
                "rpc-delete", one(deleteJson(ready.getVersion())));
        assertStringMap(delete);
        assertEquals("DELETED", delete.get("code"));
        JsonObject deleteData = JsonParser.parseString(delete.get("data"))
                .getAsJsonObject();
        assertTrue(deleteData.getAsJsonObject("resources")
                .entrySet().stream().allMatch(
                        entry -> entry.getValue().getAsInt() == 0));

        assertEquals(TeamEventType.TEAM_CREATE_ACCEPTED,
                events.get(0).getType());
        assertEquals(TeamEventType.TEAM_READY, events.get(1).getType());
        assertEquals(TeamEventType.TEAM_DELETE_ACCEPTED,
                events.get(2).getType());
        assertEquals(TeamEventType.TEAM_DELETED, events.get(3).getType());
        manager.close();
    }

    private static TeamDefinition awaitReady(TeamManager manager)
            throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            TeamDefinition definition = manager.getRuntime("team-1")
                    .map(runtime -> runtime.getDefinition()).orElse(null);
            if (definition != null
                    && definition.getState() == TeamState.READY) {
                return definition;
            }
            Thread.sleep(2L);
        }
        fail("Team did not become READY");
        return null;
    }

    private static void assertStringMap(Map<String, String> result) {
        assertNotNull(result);
        assertTrue(result.values().stream().allMatch(
                value -> value == null || value instanceof String));
    }

    private static String createJson() {
        return "{\"schemaVersion\":\"1\",\"requestId\":\"create-1\","
                + "\"teamId\":\"team-1\",\"ownerChatterId\":\"owner-1\","
                + "\"name\":\"Team\",\"members\":["
                + "{\"teamMemberId\":\"member-a\","
                + "\"sourceRobotId\":\"acp-Robot_One\","
                + "\"sourceGroupId\":\"group-a\",\"order\":0},"
                + "{\"teamMemberId\":\"member-b\","
                + "\"sourceRobotId\":\"acp-Robot_Two\","
                + "\"sourceGroupId\":\"group-b\",\"order\":1}]}";
    }

    private static String queryJson(boolean team) {
        return "{\"schemaVersion\":\"1\",\"ownerChatterId\":\"owner-1\""
                + (team ? ",\"teamId\":\"team-1\"" : "") + "}";
    }

    private static String deleteJson(long version) {
        return "{\"schemaVersion\":\"1\",\"requestId\":\"delete-1\","
                + "\"ownerChatterId\":\"owner-1\",\"teamId\":\"team-1\","
                + "\"expectedVersion\":" + version + "}";
    }

    private static String[] one(String payload) {
        return new String[]{payload};
    }

    private static AcpRobotParam robot(String name) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName(name);
        robot.setSignature("Handles " + name);
        return robot;
    }
}
