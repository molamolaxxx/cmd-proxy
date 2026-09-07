package com.mola.cmd.proxy.app.acp.task.mcp;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TaskMcpEligibilityTest {
    @Test
    public void includesStarweaveMainAndStarweaveOwnedTeamOnly() {
        assertTrue(TaskMcpEligibility.isEligible(AcpClientIdentity.starweave(
                "star-main", "star-transport", "starweave/robot",
                "starweave-instance-1", "Robot")));
        assertTrue(TaskMcpEligibility.isEligible(AcpClientIdentity.team(
                "star-member", "star-team", "team/t/member",
                "starweave-instance-1", "t", "member", "Robot")));
        assertFalse(TaskMcpEligibility.isEligible(AcpClientIdentity.main(
                "molachat-main", "Robot", "Robot")));
        assertFalse(TaskMcpEligibility.isEligible(AcpClientIdentity.team(
                "molachat-member", "molachat-team", "team/t/member",
                "molachat-user", "t", "member", "Robot")));
    }
}
