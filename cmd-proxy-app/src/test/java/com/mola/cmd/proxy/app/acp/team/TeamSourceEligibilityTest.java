package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TeamSourceEligibilityTest {

    @Test
    public void normalAndTeamOnlyRobotsShareTheSameFastTeamEligibility() {
        AcpRobotParam normal = robot("Normal", true, false, false);
        AcpRobotParam teamOnly = robot("Team Only", true, false, true);
        AcpRobotParam subAgentOnly = robot("Sub Agent", true, true, false);
        AcpRobotParam disabled = robot("Disabled", false, false, false);

        assertTrue(TeamSourceEligibility.isEligible(normal));
        assertTrue(TeamSourceEligibility.isEligible(teamOnly));
        assertFalse(TeamSourceEligibility.isEligible(subAgentOnly));
        assertFalse(TeamSourceEligibility.isEligible(disabled));
    }

    private static AcpRobotParam robot(String name, boolean enabled,
                                       boolean onlySubAgent,
                                       boolean onlyTeamMember) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName(name);
        robot.setEnabled(enabled);
        robot.setOnlySubAgent(onlySubAgent);
        robot.setOnlyTeamMember(onlyTeamMember);
        return robot;
    }
}
