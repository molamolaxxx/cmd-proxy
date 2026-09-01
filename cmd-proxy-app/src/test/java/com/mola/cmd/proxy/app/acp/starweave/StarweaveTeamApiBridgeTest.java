package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.team.MapTeamSourceRobotResolver;
import com.mola.cmd.proxy.app.acp.team.TeamClientRegistry;
import com.mola.cmd.proxy.app.acp.team.TeamManager;
import com.mola.cmd.proxy.app.acp.team.TeamStore;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberSourceDescriptor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StarweaveTeamApiBridgeTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void createsFromConfiguredSourcesWithoutAnyMainSession() throws Exception {
        String instanceId = "instance-test";
        String ownerId = StarweaveIdentity.ownerId(instanceId);
        AcpRobotParam normal = robot("Normal", false);
        AcpRobotParam teamOnly = robot("Team Only", true);
        TeamMemberSourceDescriptor normalSource = source(instanceId, ownerId, normal);
        TeamMemberSourceDescriptor teamOnlySource = source(instanceId, ownerId, teamOnly);
        List<TeamMemberSourceDescriptor> sources =
                Arrays.asList(normalSource, teamOnlySource);
        Map<String, AcpRobotParam> robots = new LinkedHashMap<>();
        robots.put(normalSource.getSourceGroupId(), normal);
        robots.put(teamOnlySource.getSourceGroupId(), teamOnly);
        TeamManager manager = new TeamManager(
                new TeamStore(temporary.newFolder("teams").toPath()),
                new TeamClientRegistry(), new MapTeamSourceRobotResolver(robots));
        StarweaveTeamApiBridge.install(manager, instanceId, () -> sources);
        try {
            JSONObject discovered = StarweaveTeamApiBridge.sources();
            assertEquals(2, discovered.getJSONArray("sources").size());
            assertTrue(discovered.getJSONArray("sources").getJSONObject(1)
                    .getBooleanValue("onlyTeamMember"));

            JSONArray members = new JSONArray();
            members.add(member(normalSource));
            members.add(member(teamOnlySource));
            JSONObject request = new JSONObject(true);
            request.put("requestId", "create-no-main-session");
            request.put("teamId", "team-no-main-session");
            request.put("name", "No MAIN Required");
            request.put("members", members);

            JSONObject result = StarweaveTeamApiBridge.create(request);

            assertTrue(result.getBooleanValue("accepted"));
            assertEquals("ACCEPTED", result.getString("code"));
            assertEquals(1, manager.snapshotDefinitions().size());
            assertEquals(2, manager.snapshotDefinitions().get(0).getMembers().size());
        } finally {
            StarweaveTeamApiBridge.clear(manager);
            manager.close();
        }
    }

    @Test
    public void rejectsSourceOutsideTheStarweaveCatalog() throws Exception {
        TeamManager manager = new TeamManager(
                new TeamStore(temporary.newFolder("rejected").toPath()),
                new TeamClientRegistry(), new MapTeamSourceRobotResolver(
                        java.util.Collections.emptyMap()));
        StarweaveTeamApiBridge.install(manager, "instance-test",
                java.util.Collections::emptyList);
        try {
            JSONObject request = new JSONObject(true);
            JSONArray members = new JSONArray();
            JSONObject member = new JSONObject(true);
            member.put("sourceGroupId", "foreign-group");
            member.put("sourceRobotId", "acp-Foreign");
            members.add(member);
            request.put("members", members);
            try {
                StarweaveTeamApiBridge.create(request);
                fail("foreign source must be rejected");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("available Starweave"));
            }
        } finally {
            StarweaveTeamApiBridge.clear(manager);
            manager.close();
        }
    }

    private static AcpRobotParam robot(String name, boolean teamOnly) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName(name);
        robot.setEnabled(true);
        robot.setOnlyTeamMember(teamOnly);
        robot.setWorkDir(".");
        return robot;
    }

    private static TeamMemberSourceDescriptor source(
            String instanceId, String ownerId, AcpRobotParam robot) {
        return new TeamMemberSourceDescriptor(ownerId,
                StarweaveIdentity.identity(instanceId, robot.getName()).getLogicalId(),
                StarweaveIdentity.acpId(robot.getName()), robot.getName(),
                robot.getName(), "", "", robot.isOnlyTeamMember());
    }

    private static JSONObject member(TeamMemberSourceDescriptor source) {
        JSONObject member = new JSONObject(true);
        member.put("sourceGroupId", source.getSourceGroupId());
        member.put("sourceRobotId", source.getSourceRobotId());
        return member;
    }
}
