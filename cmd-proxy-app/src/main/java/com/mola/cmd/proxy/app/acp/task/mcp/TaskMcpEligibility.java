package com.mola.cmd.proxy.app.acp.task.mcp;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.starweave.StarweaveIdentity;

/** Keeps the Starweave task authority out of MolaChat-owned Agent sessions. */
public final class TaskMcpEligibility {
    private TaskMcpEligibility() {
    }

    public static boolean isEligible(AcpClientIdentity identity) {
        if (identity == null) return false;
        if (identity.isStarweave()) return true;
        return identity.isTeam()
                && StarweaveIdentity.isReservedOwner(identity.getOwnerId());
    }
}
