package com.mola.cmd.proxy.app.acp.team;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.HashMap;
import java.util.Map;
import java.util.Collections;

import static org.junit.Assert.*;

public class TeamCommandHandlerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void createIsPersistedAndIdempotentForSameRequest() throws Exception {
        Fixture fixture = fixture();

        Map<String, String> first =
                fixture.handler.handleCreate("rpc-1", one(createJson("request-1", "Team")));
        Map<String, String> replay =
                fixture.handler.handleCreate("rpc-2", one(createJson("request-1", "Team")));

        assertEquals("true", first.get("accepted"));
        assertEquals("ACCEPTED", first.get("code"));
        assertEquals(first.get("data"), replay.get("data"));
        assertEquals(1L, fixture.store.loadTeam("team-1").get().getVersion());
        assertEquals(1, fixture.manager.snapshotDefinitions().size());
    }

    @Test
    public void singleMemberCreateIsAcceptedAndPersisted() throws Exception {
        Fixture fixture = fixture();

        Map<String, String> result = fixture.handler.handleCreate(
                "rpc-1", one(createSingleMemberJson("request-1", "Solo Team")));

        assertEquals("true", result.get("accepted"));
        assertEquals("ACCEPTED", result.get("code"));
        assertEquals(1, fixture.store.loadTeam("team-1").get().getMembers().size());
    }

    @Test
    public void createUsesMemberRemarkProvidedByMolaChat() throws Exception {
        Fixture fixture = fixture();
        String payload = createSingleMemberJson("request-1", "Solo Team")
                .replace("\"order\":0", "\"order\":0,\"remark\":\"  Team researcher  \"");

        Map<String, String> result = fixture.handler.handleCreate("rpc-1", one(payload));

        assertEquals("true", result.get("accepted"));
        assertEquals("Team researcher", fixture.store.loadTeam("team-1").get()
                .getMembers().get(0).getRemark());
        assertEquals("Team researcher", fixture.store.loadTeam("team-1").get()
                .getRoster().get(0).getRemark());
    }

    @Test
    public void sameRequestWithDifferentPayloadIsRejected() throws Exception {
        Fixture fixture = fixture();
        fixture.handler.handleCreate("rpc-1", one(createJson("request-1", "Team")));

        Map<String, String> result =
                fixture.handler.handleCreate("rpc-2", one(createJson("request-1", "Other")));

        assertEquals("false", result.get("accepted"));
        assertEquals("IDEMPOTENCY_CONFLICT", result.get("code"));
    }

    @Test
    public void listAndGetReturnOwnerScopedAuthoritativeProjection() throws Exception {
        Fixture fixture = fixture();
        fixture.handler.handleCreate("rpc-1", one(createJson("request-1", "Team")));

        Map<String, String> list = fixture.handler.handleList(
                "rpc-list", one("{\"schemaVersion\":\"1\",\"ownerChatterId\":\"owner-1\"}"));
        Map<String, String> get = fixture.handler.handleGet(
                "rpc-get", one("{\"schemaVersion\":\"1\",\"ownerChatterId\":\"owner-1\","
                        + "\"teamId\":\"team-1\"}"));

        JsonArray teams = JsonParser.parseString(list.get("data")).getAsJsonObject()
                .getAsJsonArray("teams");
        JsonObject team = JsonParser.parseString(get.get("data")).getAsJsonObject()
                .getAsJsonObject("team");
        assertEquals("rpc-list", list.get("requestId"));
        assertEquals(1, teams.size());
        assertEquals("team-1", team.get("teamId").getAsString());
        assertEquals("1", get.get("teamVersion"));
    }

    @Test
    public void persistedDefinitionAndOperationSurviveManagerRestart() throws Exception {
        Fixture fixture = fixture();
        Map<String, String> created =
                fixture.handler.handleCreate("rpc-1", one(createJson("request-1", "Team")));
        fixture.manager.close();

        TeamManager recovered = new TeamManager(fixture.store,
                new TeamClientRegistry(), new MapTeamSourceRobotResolver(fixture.sources));
        assertEquals(1, recovered.recoverPersistedDefinitions());
        TeamCommandHandler recoveredHandler =
                new TeamCommandHandler(recovered, "team-acp-instance");

        Map<String, String> replay = recoveredHandler.handleCreate(
                "rpc-2", one(createJson("request-1", "Team")));
        Map<String, String> get = recoveredHandler.handleGet(
                "rpc-get", one("{\"schemaVersion\":\"1\",\"ownerChatterId\":\"owner-1\","
                        + "\"teamId\":\"team-1\"}"));
        Map<String, String> list = recoveredHandler.handleList(
                "rpc-list", one("{\"schemaVersion\":\"1\","
                        + "\"ownerChatterId\":\"owner-1\"}"));

        assertEquals(created.get("data"), replay.get("data"));
        assertEquals("true", get.get("accepted"));
        assertEquals(1, JsonParser.parseString(list.get("data"))
                .getAsJsonObject().getAsJsonArray("teams").size());
    }

    @Test
    public void sourceRobotMismatchUsesStructuredErrorCode() throws Exception {
        Fixture fixture = fixture();
        String payload = createJson("request-1", "Team")
                .replace("\"acp-Robot_One\"", "\"acp-Wrong\"");

        Map<String, String> result =
                fixture.handler.handleCreate("rpc-1", one(payload));

        assertEquals("false", result.get("accepted"));
        assertEquals("SOURCE_ROBOT_MISMATCH", result.get("code"));
    }

    @Test
    public void mixedFragmentPersistsLocalMembersAndGlobalRoster() throws Exception {
        Fixture fixture = fixture();
        AcpRobotParam shared = robot("Remote Robot");
        shared.setTeamSharedWithChatterIds(Collections.singletonList("owner-1"));
        String group = TeamSharedSourceIds.groupId("instance-b", "acp-Remote_Robot");
        fixture.sources.put(group, shared);
        String payload = "{\"schemaVersion\":\"1\",\"requestId\":\"mixed-1\","
                + "\"teamId\":\"mixed-team\",\"ownerChatterId\":\"owner-1\","
                + "\"name\":\"Mixed\",\"mixedPlacement\":true,"
                + "\"members\":[{\"teamMemberId\":\"remote-1\","
                + "\"sourceRobotId\":\"acp-Remote_Robot\",\"sourceGroupId\":\""
                + group + "\",\"order\":1}],"
                + "\"roster\":[{\"teamMemberId\":\"home-1\","
                + "\"acpClientId\":\"team-acp-home-1\",\"displayName\":\"Home\","
                + "\"remark\":\"home\",\"order\":0},{\"teamMemberId\":\"remote-1\","
                + "\"acpClientId\":\"team-acp-remote-1\",\"displayName\":\"Remote\","
                + "\"remark\":\"remote\",\"order\":1}]}";

        Map<String, String> result = fixture.handler.handleCreate("rpc", one(payload));

        assertEquals("true", result.get("accepted"));
        assertTrue(fixture.store.loadTeam("mixed-team").get().isMixedPlacement());
        assertEquals(2, fixture.store.loadTeam("mixed-team").get().getRoster().size());
    }

    @Test
    public void mixedFragmentRejectsOwnerWithoutGrant() throws Exception {
        Fixture fixture = fixture();
        AcpRobotParam shared = robot("Remote Robot");
        String group = TeamSharedSourceIds.groupId("instance-b", "acp-Remote_Robot");
        fixture.sources.put(group, shared);
        String payload = "{\"schemaVersion\":\"1\",\"requestId\":\"mixed-1\","
                + "\"teamId\":\"mixed-team\",\"ownerChatterId\":\"owner-1\","
                + "\"name\":\"Mixed\",\"mixedPlacement\":true,"
                + "\"members\":[{\"teamMemberId\":\"remote-1\","
                + "\"sourceRobotId\":\"acp-Remote_Robot\",\"sourceGroupId\":\""
                + group + "\",\"order\":1}],"
                + "\"roster\":[{\"teamMemberId\":\"home-1\","
                + "\"acpClientId\":\"team-acp-home-1\",\"displayName\":\"Home\","
                + "\"order\":0},{\"teamMemberId\":\"remote-1\","
                + "\"acpClientId\":\"team-acp-remote-1\",\"displayName\":\"Remote\","
                + "\"order\":1}]}";

        Map<String, String> result = fixture.handler.handleCreate("rpc", one(payload));
        assertEquals("false", result.get("accepted"));
        assertEquals("TEAM_GRANT_REVOKED", result.get("code"));
    }

    @Test
    public void rejectsAnythingOtherThanOneJsonArgument() throws Exception {
        Fixture fixture = fixture();
        Map<String, String> result =
                fixture.handler.handleList("rpc-list", new String[0]);

        assertEquals("false", result.get("accepted"));
        assertEquals("VALIDATION_ERROR", result.get("code"));
    }

    private Fixture fixture() throws Exception {
        TeamStore store = new TeamStore(temporaryFolder.newFolder("teams").toPath());
        Map<String, AcpRobotParam> sources = new HashMap<>();
        sources.put("group-1", robot("Robot One"));
        sources.put("group-2", robot("Robot Two"));
        TeamManager manager = new TeamManager(store, new TeamClientRegistry(),
                new MapTeamSourceRobotResolver(sources));
        return new Fixture(store, sources, manager,
                new TeamCommandHandler(manager, "team-acp-instance"));
    }

    private static AcpRobotParam robot(String name) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName(name);
        robot.setSignature("Handles " + name);
        return robot;
    }

    private static String[] one(String json) {
        return new String[]{json};
    }

    private static String createJson(String requestId, String name) {
        return "{"
                + "\"schemaVersion\":\"1\","
                + "\"requestId\":\"" + requestId + "\","
                + "\"teamId\":\"team-1\","
                + "\"ownerChatterId\":\"owner-1\","
                + "\"name\":\"" + name + "\","
                + "\"members\":["
                + "{\"teamMemberId\":\"member-1\",\"sourceRobotId\":\"acp-Robot_One\","
                + "\"sourceGroupId\":\"group-1\",\"order\":0},"
                + "{\"teamMemberId\":\"member-2\",\"sourceRobotId\":\"acp-Robot_Two\","
                + "\"sourceGroupId\":\"group-2\",\"order\":1}"
                + "]}";
    }

    private static String createSingleMemberJson(String requestId, String name) {
        return "{"
                + "\"schemaVersion\":\"1\","
                + "\"requestId\":\"" + requestId + "\","
                + "\"teamId\":\"team-1\","
                + "\"ownerChatterId\":\"owner-1\","
                + "\"name\":\"" + name + "\","
                + "\"members\":["
                + "{\"teamMemberId\":\"member-1\",\"sourceRobotId\":\"acp-Robot_One\","
                + "\"sourceGroupId\":\"group-1\",\"order\":0}"
                + "]}";
    }

    private static final class Fixture {
        private final TeamStore store;
        private final Map<String, AcpRobotParam> sources;
        private final TeamManager manager;
        private final TeamCommandHandler handler;

        private Fixture(TeamStore store, Map<String, AcpRobotParam> sources,
                        TeamManager manager, TeamCommandHandler handler) {
            this.store = store;
            this.sources = sources;
            this.manager = manager;
            this.handler = handler;
        }
    }
}
