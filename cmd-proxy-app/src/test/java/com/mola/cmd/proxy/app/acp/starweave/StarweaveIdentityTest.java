package com.mola.cmd.proxy.app.acp.starweave;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.acpclient.ClientSurface;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class StarweaveIdentityTest {

    @Test
    public void ownerAndGroupAreStableAndEnvironmentScoped() {
        assertEquals("starweave-env-a", StarweaveIdentity.ownerId("env-a"));
        assertEquals("acp-Code_Agentstarweave-env-a",
                StarweaveIdentity.groupId("env-a", "Code Agent"));
        assertNotEquals(StarweaveIdentity.groupId("env-a", "Code Agent"),
                StarweaveIdentity.groupId("env-b", "Code Agent"));
    }

    @Test
    public void identityCarriesExplicitStarweaveDimensions() {
        AcpClientIdentity identity = StarweaveIdentity.identity(
                "env-a", "结算/Agent");

        assertEquals(ClientSurface.STARWEAVE, identity.getSurface());
        assertEquals("starweave-env-a", identity.getOwnerId());
        assertEquals("结算/Agent", identity.getSourceRobotName());
        assertTrue(identity.getHistoryNamespace().matches(
                "starweave/robot-[a-f0-9]{24}"));
        assertTrue(identity.getTransportGroup().startsWith(
                "starweave-local:env-a:robot-"));
        assertTrue(identity.getLogicalId().matches(
                "acp-key-[a-f0-9]{24}starweave-env-a"));
    }

    @Test
    public void unicodeDisplayNameUsesStableSafeLogicalId() {
        String first = StarweaveIdentity.groupId("env-a", "Sales专家");
        String second = StarweaveIdentity.groupId("env-a", "Sales专家");

        assertEquals(first, second);
        assertTrue(first.matches("[a-zA-Z0-9._-]+"));
        assertTrue(first.startsWith("acp-key-"));
        assertNotEquals(first, StarweaveIdentity.groupId("env-a", "Sales顾问"));
    }

    @Test
    public void reservedHashPrefixCannotCollideWithDerivedLogicalId() {
        String literalPrefix = StarweaveIdentity.groupId(
                "env-a", "key-0123456789abcdef01234567");

        assertTrue(literalPrefix.startsWith("acp-key-"));
        assertFalse(literalPrefix.contains("acp-key-0123456789abcdef01234567"));
    }

    @Test
    public void starweaveNamespaceIsReserved() {
        assertTrue(StarweaveIdentity.isReservedOwner("starweave-env-a"));
        assertFalse(StarweaveIdentity.isReservedOwner("normal-chatter"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void reservedNamespaceCannotBeConfiguredAsMolaChatChatter() {
        StarweaveIdentity.validateMolaChatChatterIds(Arrays.asList(
                "normal-chatter", "starweave-env-a"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidInstanceIdIsRejected() {
        StarweaveIdentity.ownerId("env:a");
    }
}
