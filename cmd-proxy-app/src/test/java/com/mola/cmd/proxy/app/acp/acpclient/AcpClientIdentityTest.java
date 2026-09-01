package com.mola.cmd.proxy.app.acp.acpclient;

import org.junit.Test;

import static org.junit.Assert.*;

public class AcpClientIdentityTest {

    @Test
    public void structuredAgentAddressKeepsSurfaceAndOwnerExplicit() {
        AgentAddress address = new AgentAddress("instance-1", ClientSurface.STARWEAVE,
                "starweave-instance-1", "Robot One");
        assertEquals("instance-1/STARWEAVE/starweave-instance-1/Robot One",
                address.canonical());
        assertEquals("STARWEAVE", address.toJson().getString("surface"));
    }

    @Test
    public void mainIdentityKeepsLegacyGroupAsLogicalAndTransportIdentity() {
        AcpClientIdentity identity =
                AcpClientIdentity.main("group-1", "Robot One", "Robot One");

        assertEquals(AcpClientIdentity.Scope.MAIN, identity.getScope());
        assertEquals(ClientSurface.MOLACHAT, identity.getSurface());
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
        assertEquals(ClientSurface.TEAM, identity.getSurface());
        assertEquals("team-acp-member-1", identity.getLogicalId());
        assertEquals("team-acp-instance-1", identity.getTransportGroup());
        assertEquals("team/team-1/member-1", identity.getHistoryNamespace());
        assertEquals("team-1", identity.getTeamId());
        assertEquals("member-1", identity.getTeamMemberId());
    }

    @Test
    public void starweaveIdentityIsMainButUsesIndependentSurfaceAndOwner() {
        AcpClientIdentity identity = AcpClientIdentity.starweave(
                "local-group", "local-transport", "starweave/robot-1",
                "starweave-instance-1", "Robot One");

        assertEquals(AcpClientIdentity.Scope.MAIN, identity.getScope());
        assertEquals(ClientSurface.STARWEAVE, identity.getSurface());
        assertEquals("starweave-instance-1", identity.getOwnerId());
        assertEquals("starweave/robot-1", identity.getHistoryNamespace());
        assertTrue(identity.isStarweave());
        assertFalse(identity.isTeam());
    }

    @Test(expected = IllegalArgumentException.class)
    public void starweaveIdentityRequiresOwner() {
        AcpClientIdentity.starweave(
                "local-group", "local-transport", "starweave/robot-1",
                " ", "Robot One");
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
