package com.mola.cmd.proxy.app.acp.team.listener;

import com.mola.cmd.proxy.app.acp.team.model.TeamError;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberState;

@FunctionalInterface
public interface TeamMemberStateObserver {

    TeamMemberStateObserver NOOP = (teamId, memberId, state, error) -> {
    };

    void onState(String teamId, String teamMemberId,
                 TeamMemberState state, TeamError error);
}
