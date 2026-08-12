package com.mola.cmd.proxy.app.acp.team.protocol;

public final class TeamTransportCapabilities {
    private final boolean mixedTeamFragment;
    private final boolean mixedTeamTalkToDeliver;

    public TeamTransportCapabilities(boolean mixedTeamFragment,
                                     boolean mixedTeamTalkToDeliver) {
        this.mixedTeamFragment = mixedTeamFragment;
        this.mixedTeamTalkToDeliver = mixedTeamTalkToDeliver;
    }

    public boolean isMixedTeamFragment() { return mixedTeamFragment; }
    public boolean isMixedTeamTalkToDeliver() { return mixedTeamTalkToDeliver; }
}
