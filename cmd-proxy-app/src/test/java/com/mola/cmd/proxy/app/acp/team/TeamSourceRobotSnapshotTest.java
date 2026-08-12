package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.team.model.TeamErrorCode;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCreateSpec;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Collections;

import static org.junit.Assert.*;

public class TeamSourceRobotSnapshotTest {

    @Test
    public void resolverTrimsTransportIdentityFields() throws Exception {
        AcpRobotParam robot = robot();
        Map<String, AcpRobotParam> robots = new HashMap<>();
        robots.put("group-1", robot);

        AcpRobotParam resolved = new MapTeamSourceRobotResolver(robots).resolve(
                new TeamMemberCreateSpec("member-1", " acp-Robot_One ",
                        " group-1 ", 0));

        assertSame(robot, resolved);
    }

    @Test
    public void resolverDistinguishesMissingGroupFromStaleRobotIdentity() throws Exception {
        Map<String, AcpRobotParam> robots = new HashMap<>();
        robots.put("group-1", robot());
        MapTeamSourceRobotResolver resolver = new MapTeamSourceRobotResolver(robots);

        assertResolutionCode(resolver,
                new TeamMemberCreateSpec("member-1", "acp-Robot_One",
                        "missing-group", 0),
                TeamErrorCode.SOURCE_ROBOT_NOT_FOUND);
        assertResolutionCode(resolver,
                new TeamMemberCreateSpec("member-1", "acp-Stale_Name",
                        "group-1", 0),
                TeamErrorCode.SOURCE_ROBOT_MISMATCH);
    }

    @Test
    public void resolverRejectsDisabledRobotEvenWhenMapStillContainsIt() throws Exception {
        AcpRobotParam disabled = robot();
        disabled.setEnabled(false);
        Map<String, AcpRobotParam> robots = new HashMap<>();
        robots.put("group-1", disabled);

        assertResolutionCode(new MapTeamSourceRobotResolver(robots), spec(),
                TeamErrorCode.SOURCE_ROBOT_MISMATCH);
    }

    @Test
    public void resolverAcceptsEnabledTeamOnlyRobot() throws Exception {
        AcpRobotParam teamOnly = robot();
        teamOnly.setOnlyTeamMember(true);
        Map<String, AcpRobotParam> robots = new HashMap<>();
        robots.put("group-1", teamOnly);

        assertSame(teamOnly, new MapTeamSourceRobotResolver(robots).resolve(spec()));
    }

    @Test
    public void resolverRejectsOnlySubAgentRobot() throws Exception {
        AcpRobotParam onlySubAgent = robot();
        onlySubAgent.setOnlySubAgent(true);
        Map<String, AcpRobotParam> robots = new HashMap<>();
        robots.put("group-1", onlySubAgent);

        assertResolutionCode(new MapTeamSourceRobotResolver(robots), spec(),
                TeamErrorCode.SOURCE_ROBOT_MISMATCH);
    }

    @Test
    public void snapshotDeepCopiesFullRuntimeConfigAndKeepsOnlyFingerprintInDefinition()
            throws Exception {
        Map<String, AcpRobotParam> robots = new HashMap<>();
        AcpRobotParam source = robot();
        robots.put("group-1", source);
        MapTeamSourceRobotResolver resolver = new MapTeamSourceRobotResolver(robots);

        TeamSourceRobotSnapshot snapshot = resolver.snapshot(spec());
        String fingerprint = snapshot.getConfigFingerprint();
        source.setModel("changed-after-capture");

        assertEquals("original-model", snapshot.copyRobotParam().getModel());
        assertEquals("secret-key", snapshot.copyRobotParam().getApiKey());
        assertEquals(64, fingerprint.length());
        TeamMemberDefinition persisted = member(fingerprint);
        String persistedText = new com.google.gson.Gson().toJson(persisted);
        assertFalse(persistedText.contains("secret-key"));
        assertFalse(persistedText.contains("original-model"));
        assertTrue(persistedText.contains(fingerprint));
    }

    @Test
    public void restoreUsesCurrentConfigurationAfterServiceRefresh() throws Exception {
        Map<String, AcpRobotParam> robots = new HashMap<>();
        AcpRobotParam source = robot();
        robots.put("group-1", source);
        MapTeamSourceRobotResolver resolver = new MapTeamSourceRobotResolver(robots);
        String fingerprint = resolver.snapshot(spec()).getConfigFingerprint();
        source.setModel("changed");

        TeamSourceRobotSnapshot restored = resolver.restore(member(fingerprint));

        assertEquals("changed", restored.copyRobotParam().getModel());
        assertNotEquals(fingerprint, restored.getConfigFingerprint());
    }

    @Test
    public void teamOnlyRoleDoesNotChangeRuntimeFingerprint() throws Exception {
        Map<String, AcpRobotParam> robots = new HashMap<>();
        AcpRobotParam source = robot();
        robots.put("group-1", source);
        MapTeamSourceRobotResolver resolver = new MapTeamSourceRobotResolver(robots);
        String before = resolver.snapshot(spec()).getConfigFingerprint();

        source.setOnlyTeamMember(true);

        assertEquals(before, resolver.snapshot(spec()).getConfigFingerprint());
    }

    @Test
    public void sharedSourceRequiresExplicitOwnerGrant() throws Exception {
        AcpRobotParam shared = robot();
        shared.setTeamSharedWithChatterIds(Collections.singletonList("owner-a"));
        String group = TeamSharedSourceIds.groupId("instance-b", "acp-Robot_One");
        Map<String, AcpRobotParam> robots = new HashMap<>();
        robots.put(group, shared);
        MapTeamSourceRobotResolver resolver = new MapTeamSourceRobotResolver(robots);
        TeamMemberCreateSpec spec = new TeamMemberCreateSpec(
                "member-1", "acp-Robot_One", group, 0);

        assertNotNull(resolver.snapshot(spec, "owner-a", true));
        assertFalse(resolver.isGrantActive(group, "owner-b"));
        try {
            resolver.snapshot(spec, "owner-b", true);
            fail("expected grant rejection");
        } catch (TeamSourceResolutionException e) {
            assertEquals(TeamErrorCode.TEAM_GRANT_REVOKED, e.getCode());
        }
    }

    private static AcpRobotParam robot() {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("Robot One");
        robot.setModel("original-model");
        robot.setApiKey("secret-key");
        robot.setProxyEnabled(true);
        robot.setHttpProxy("http://proxy");
        return robot;
    }

    private static TeamMemberCreateSpec spec() {
        return new TeamMemberCreateSpec(
                "member-1", "acp-Robot_One", "group-1", 0);
    }

    private static TeamMemberDefinition member(String fingerprint) {
        return new TeamMemberDefinition(
                "member-1", "acp-Robot_One", "group-1",
                "Robot One", "Robot One", "", 0, "remark", fingerprint);
    }

    private static void assertResolutionCode(
            MapTeamSourceRobotResolver resolver, TeamMemberCreateSpec spec,
            TeamErrorCode expected) throws Exception {
        try {
            resolver.resolve(spec);
            fail("expected " + expected);
        } catch (TeamSourceResolutionException e) {
            assertEquals(expected, e.getCode());
        }
    }
}
