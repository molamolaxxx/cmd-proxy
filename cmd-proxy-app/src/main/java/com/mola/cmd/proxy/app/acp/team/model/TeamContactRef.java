package com.mola.cmd.proxy.app.acp.team.model;

import java.util.Objects;

/**
 * Team 临时通讯录条目。路由身份只使用不可变 member/client ID，
 * displayName 与 remark 仅用于模型展示，不参与模糊匹配。
 */
public final class TeamContactRef {

    private final String targetTeamMemberId;
    private final String targetAcpClientId;
    private final String displayName;
    private final String remark;

    public TeamContactRef(String targetTeamMemberId, String targetAcpClientId,
                          String displayName, String remark) {
        this.targetTeamMemberId =
                TeamError.requireText(targetTeamMemberId, "targetTeamMemberId");
        this.targetAcpClientId =
                TeamError.requireText(targetAcpClientId, "targetAcpClientId");
        this.displayName = TeamError.requireText(displayName, "displayName");
        this.remark = remark == null ? "" : remark.trim();
    }

    public static TeamContactRef from(TeamMemberDefinition member) {
        Objects.requireNonNull(member, "member");
        return new TeamContactRef(member.getTeamMemberId(), member.getAcpClientId(),
                member.getDisplayName(), member.getRemark());
    }

    public String getTargetTeamMemberId() {
        return targetTeamMemberId;
    }

    public String getTargetAcpClientId() {
        return targetAcpClientId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRemark() {
        return remark;
    }
}
