package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCreateSpec;

@FunctionalInterface
public interface TeamSourceRobotResolver {

    AcpRobotParam resolve(TeamMemberCreateSpec spec) throws TeamSourceResolutionException;

    default TeamSourceRobotSnapshot snapshot(TeamMemberCreateSpec spec)
            throws TeamSourceResolutionException {
        AcpRobotParam robot = resolve(spec);
        return TeamSourceRobotSnapshots.capture(spec.getSourceGroupId(),
                spec.getSourceRobotId(), robot);
    }

    default TeamSourceRobotSnapshot restore(TeamMemberDefinition member)
            throws TeamSourceResolutionException {
        TeamMemberCreateSpec spec = new TeamMemberCreateSpec(
                member.getTeamMemberId(), member.getSourceRobotId(),
                member.getSourceGroupId(), member.getOrder());
        TeamSourceRobotSnapshot snapshot = snapshot(spec);
        if (!member.getConfigFingerprint().equals(snapshot.getConfigFingerprint())) {
            throw new TeamSourceResolutionException(
                    com.mola.cmd.proxy.app.acp.team.model.TeamErrorCode.VERSION_CONFLICT,
                    "source robot configuration fingerprint changed");
        }
        return snapshot;
    }
}
