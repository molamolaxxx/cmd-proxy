package com.mola.cmd.proxy.app.acp.acpclient;

import org.junit.Test;

import static org.junit.Assert.*;

public class AcpClientIdentityTest {

    @Test
    public void mainIdentityKeepsLegacyGroupAsLogicalAndTransportIdentity() {
        AcpClientIdentity identity =
                AcpClientIdentity.main("group-1", "Robot One", "Robot One");

        assertEquals(AcpClientIdentity.Scope.MAIN, identity.getScope());
        assertEquals("group-1", identity.getLogicalId());
        assertEquals("group-1", identity.getTransportGroup());
        assertEquals("Robot One", identity.getHistoryNamespace());
        assertEquals("Robot One", identity.getSourceRobotName());
        assertFalse(identity.isTeam());
    }

    @Test
    public void teamIdentitySeparatesLogicalTransportAndHistoryDimensions() {
        AcpClientIdentity identity = AcpClientIdentity.team(
                "team-acp-member-1",
                "team-acp-instance-1",
                "team/team-1/member-1",
                "chatter-1",
                "team-1",
                "member-1",
                "Source Robot");

        assertTrue(identity.isTeam());
        assertEquals("team-acp-member-1", identity.getLogicalId());
        assertEquals("team-acp-instance-1", identity.getTransportGroup());
        assertEquals("team/team-1/member-1", identity.getHistoryNamespace());
        assertEquals("team-1", identity.getTeamId());
        assertEquals("member-1", identity.getTeamMemberId());
    }

    @Test(expected = IllegalArgumentException.class)
    public void teamIdentityRequiresTeamId() {
        AcpClientIdentity.builder(
                        AcpClientIdentity.Scope.TEAM,
                        "member-1",
                        "transport-1",
                        "team/team-1/member-1")
                .teamMemberId("member-1")
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonTeamIdentityRejectsTeamFields() {
        AcpClientIdentity.builder(
                        AcpClientIdentity.Scope.MAIN,
                        "group-1",
                        "group-1",
                        "Robot One")
                .teamId("team-1")
                .teamMemberId("member-1")
                .build();
    }
}
