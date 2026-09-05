package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.team.MapTeamSourceRobotResolver;
import com.mola.cmd.proxy.app.acp.team.TeamClientRegistry;
import com.mola.cmd.proxy.app.acp.team.TeamManager;
import com.mola.cmd.proxy.app.acp.team.TeamStore;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberSourceDescriptor;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamCreateCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCreateSpec;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamRosterMemberSpec;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
            JSONObject normalMember = member(normalSource);
            normalMember.put("remark", "负责产品需求分析");
            members.add(normalMember);
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
            assertEquals("负责产品需求分析", manager.snapshotDefinitions().get(0)
                    .getMembers().get(0).getRemark());
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

    @Test
    public void coordinatedMixedEventsAcceptRemoteRosterMembersAndRejectWrongOwner()
            throws Exception {
        String instanceId = "instance-home";
        String ownerId = StarweaveIdentity.ownerId(instanceId);
        AcpRobotParam local = robot("Local", false);
        TeamMemberSourceDescriptor localSource = source(instanceId, ownerId, local);
        Map<String, AcpRobotParam> robots = new LinkedHashMap<>();
        robots.put(localSource.getSourceGroupId(), local);
        TeamManager manager = new TeamManager(
                new TeamStore(temporary.newFolder("mixed-events").toPath()),
                new TeamClientRegistry(), new MapTeamSourceRobotResolver(robots));
        StarweaveTeamApiBridge.install(manager, instanceId,
                () -> java.util.Collections.singletonList(localSource));
        try {
            TeamCreateCommand command = new TeamCreateCommand("1", "request-mixed",
                    "team-mixed", ownerId, "Mixed",
                    java.util.Collections.singletonList(new TeamMemberCreateSpec(
                            "member-local", localSource.getSourceRobotId(),
                            localSource.getSourceGroupId(), 0)), true,
                    Arrays.asList(
                            new TeamRosterMemberSpec("member-local", "team-acp-member-local",
                                    "Local", "", 0),
                            new TeamRosterMemberSpec("member-remote", "team-acp-member-remote",
                                    "Remote", "", 1)));
            assertTrue(manager.create(command, "team-acp-" + instanceId).isAccepted());
            TeamEventEnvelope localEvent = TeamEventEnvelope.next(
                    manager.getRuntime("team-mixed").get(), "member-local",
                    "team-acp-member-local", TeamEventType.MESSAGE_COMPLETE,
                    java.util.Collections.singletonMap("content", "done"));
            assertTrue(StarweaveTeamApiBridge.requiresCoordinator(localEvent));
            JSONObject offlineList = StarweaveTeamApiBridge.list();
            assertTrue(offlineList.getJSONObject("data").getJSONArray("teams")
                    .getJSONObject(0).getBooleanValue("coordinated"));

            JSONObject remoteEvent = new JSONObject(true);
            remoteEvent.put("eventId", "remote-event-1");
            remoteEvent.put("eventSeq", 7L);
            remoteEvent.put("transportGroup", "team-acp-instance-remote");
            remoteEvent.put("teamId", "team-mixed");
            remoteEvent.put("teamMemberId", "member-remote");
            remoteEvent.put("acpClientId", "team-acp-member-remote");
            remoteEvent.put("type", "MESSAGE_COMPLETE");
            JSONObject wrapper = new JSONObject(true);
            wrapper.put("ownerChatterId", ownerId);
            wrapper.put("event", remoteEvent);
            assertEquals("true", StarweaveTeamApiBridge.acceptGatewayEvent(
                    "rpc-event", new String[]{wrapper.toJSONString()}).get("accepted"));
            assertEquals(1, StarweaveTeamApiBridge.events(
                    0L, "team-mixed", "member-remote").size());

            wrapper.put("ownerChatterId", "starweave-forged");
            try {
                StarweaveTeamApiBridge.acceptGatewayEvent(
                        "rpc-forged", new String[]{wrapper.toJSONString()});
                fail("wrong Starweave owner must be rejected");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("owner mismatch"));
            }
        } finally {
            StarweaveTeamApiBridge.clear(manager);
            manager.close();
        }
    }

    @Test
    public void coordinatorReplacesLocalSourceAndReceivesStableMixedSelection()
            throws Exception {
        String instanceId = "instance-home";
        String ownerId = StarweaveIdentity.ownerId(instanceId);
        AcpRobotParam local = robot("Local", false);
        TeamMemberSourceDescriptor localSource = source(instanceId, ownerId, local);
        Map<String, AcpRobotParam> robots = new LinkedHashMap<>();
        robots.put(localSource.getSourceGroupId(), local);
        TeamManager manager = new TeamManager(
                new TeamStore(temporary.newFolder("coordinated-sources").toPath()),
                new TeamClientRegistry(), new MapTeamSourceRobotResolver(robots));
        AtomicReference<JSONObject> createPayload = new AtomicReference<>();
        final StarweaveTeamGateway[] holder = new StarweaveTeamGateway[1];
        holder[0] = new StarweaveTeamGateway(instanceId, "team-acp-" + instanceId,
                200L, 200L, (command, group, callback) -> {
            JSONObject data = new JSONObject(true);
            String operation = callback.getResultMap().get("operation");
            if ("sources".equals(operation)) {
                JSONArray coordinated = new JSONArray();
                JSONObject replacement = sourceJson(localSource, instanceId);
                replacement.put("transportGroup", "team-acp-" + instanceId);
                replacement.put("sourceLabel", "本环境 · Starweave");
                coordinated.add(replacement);
                JSONObject remote = new JSONObject(true);
                remote.put("cmdProxyInstanceId", "instance-remote");
                remote.put("transportGroup", "team-acp-instance-remote");
                remote.put("sourceGroupId", "team-shared-instance-remote-acp-Remote");
                remote.put("sourceRobotId", "acp-Remote");
                remote.put("displayName", "Remote");
                remote.put("sourceLabel", "远程环境 · 共享 Agent");
                coordinated.add(remote);
                data.put("sources", coordinated);
            } else if ("create".equals(operation)) {
                createPayload.set(JSON.parseObject(
                        callback.getResultMap().get("payload")));
                JSONObject team = new JSONObject(true);
                team.put("teamId", "team-coordinated");
                data.put("team", team);
            } else {
                fail("unexpected gateway operation: " + operation);
            }
            JSONObject response = new JSONObject(true);
            response.put("requestId", callback.getCmdId());
            response.put("accepted", true);
            response.put("code", "OK");
            response.put("data", data);
            holder[0].acceptResult("rpc-result",
                    new String[]{response.toJSONString()});
        });
        JSONObject ready = new JSONObject(true);
        ready.put("instanceId", instanceId);
        ready.put("ownerChatterId", ownerId);
        holder[0].acceptReady("ready", new String[]{ready.toJSONString()});
        StarweaveTeamApiBridge.install(manager, instanceId,
                () -> java.util.Collections.singletonList(localSource), holder[0]);
        try {
            JSONArray sources = StarweaveTeamApiBridge.sources()
                    .getJSONArray("sources");
            assertEquals(2, sources.size());
            assertEquals("team-acp-" + instanceId,
                    sources.getJSONObject(0).getString("transportGroup"));
            assertTrue(sources.getJSONObject(0).getBooleanValue("coordinated"));

            JSONArray members = new JSONArray();
            for (int i = 0; i < sources.size(); i++) {
                JSONObject selected = new JSONObject(sources.getJSONObject(i));
                selected.put("remark", i == 0 ? "本地实现" : "远程复核");
                members.add(selected);
            }
            JSONObject request = new JSONObject(true);
            request.put("requestId", "create-coordinated");
            request.put("name", "Mixed");
            request.put("members", members);

            JSONObject result = StarweaveTeamApiBridge.create(request);

            assertTrue(result.getBooleanValue("accepted"));
            assertEquals("team-coordinated", result.getJSONObject("data")
                    .getJSONObject("team").getString("teamId"));
            assertEquals("instance-remote", createPayload.get()
                    .getJSONArray("members").getJSONObject(1)
                    .getString("cmdProxyInstanceId"));
            assertEquals("远程复核", createPayload.get()
                    .getJSONArray("members").getJSONObject(1)
                    .getString("remark"));
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

    private static JSONObject sourceJson(TeamMemberSourceDescriptor source,
                                         String instanceId) {
        JSONObject value = new JSONObject(true);
        value.put("cmdProxyInstanceId", instanceId);
        value.put("ownerChatterId", source.getOwnerChatterId());
        value.put("sourceGroupId", source.getSourceGroupId());
        value.put("sourceRobotId", source.getSourceRobotId());
        value.put("robotName", source.getRobotName());
        value.put("displayName", source.getDisplayName());
        value.put("avatar", source.getAvatar());
        value.put("remark", source.getRemark());
        value.put("onlyTeamMember", source.isOnlyTeamMember());
        return value;
    }
}
