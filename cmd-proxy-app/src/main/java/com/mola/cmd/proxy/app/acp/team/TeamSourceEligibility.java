package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;

/** Shared local Fast Team source eligibility for every UI surface. */
public final class TeamSourceEligibility {

    private TeamSourceEligibility() { }

    public static boolean isEligible(AcpRobotParam robot) {
        return robot != null && robot.isEnabled() && !robot.isOnlySubAgent()
                && robot.getName() != null && !robot.getName().trim().isEmpty();
    }
}
