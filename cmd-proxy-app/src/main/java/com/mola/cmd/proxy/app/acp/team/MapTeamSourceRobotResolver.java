package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.team.model.TeamErrorCode;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCreateSpec;

import java.util.Map;

/**
 * 使用普通 groupId -> robot 注册表校验 Team 来源，只读取配置，不创建主 client。
 */
public final class MapTeamSourceRobotResolver implements TeamSourceRobotResolver {

    private final Map<String, AcpRobotParam> groupRobotMap;

    public MapTeamSourceRobotResolver(Map<String, AcpRobotParam> groupRobotMap) {
        this.groupRobotMap = java.util.Objects.requireNonNull(groupRobotMap, "groupRobotMap");
    }

    @Override
    public AcpRobotParam resolve(TeamMemberCreateSpec spec)
            throws TeamSourceResolutionException {
        String sourceGroupId = spec.getSourceGroupId() == null
                ? null : spec.getSourceGroupId().trim();
        String sourceRobotId = spec.getSourceRobotId() == null
                ? null : spec.getSourceRobotId().trim();
        AcpRobotParam robot = groupRobotMap.get(sourceGroupId);
        if (robot == null || robot.getName() == null || robot.getName().trim().isEmpty()) {
            throw new TeamSourceResolutionException(
                    TeamErrorCode.SOURCE_ROBOT_NOT_FOUND,
                    "sourceGroupId does not resolve to an active ordinary ACP robot");
        }
        String expectedRobotId = "acp-" + robot.getName()
                .replace(" ", "_").replace("\u3000", "_");
        if (!expectedRobotId.equals(sourceRobotId)) {
            throw new TeamSourceResolutionException(
                    TeamErrorCode.SOURCE_ROBOT_MISMATCH,
                    "sourceRobotId does not match sourceGroupId");
        }
        if (!robot.isEnabled() || robot.isOnlySubAgent()) {
            throw new TeamSourceResolutionException(
                    TeamErrorCode.SOURCE_ROBOT_MISMATCH,
                    "source robot is not an enabled ordinary ACP robot");
        }
        return robot;
    }
}
