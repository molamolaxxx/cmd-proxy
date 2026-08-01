package com.mola.cmd.proxy.app.acp.acpclient;

import java.util.Objects;

/**
 * ACP Client 的显式业务身份。
 * <p>
 * 过去 {@code groupId} 同时承担逻辑身份、RPC transport、历史目录等职责。
 * 该对象将这些维度拆开，为 MAIN 与 TEAM client 的严格隔离提供基础。
 */
public final class AcpClientIdentity {

    public enum Scope {
        MAIN,
        TEAM,
        SUB_AGENT,
        MEMORY,
        ABILITY
    }

    private final Scope scope;
    private final String logicalId;
    private final String transportGroup;
    private final String historyNamespace;
    private final String ownerChatterId;
    private final String teamId;
    private final String teamMemberId;
    private final String sourceRobotName;

    private AcpClientIdentity(Builder builder) {
        this.scope = Objects.requireNonNull(builder.scope, "scope");
        this.logicalId = requireText(builder.logicalId, "logicalId");
        this.transportGroup = requireText(builder.transportGroup, "transportGroup");
        this.historyNamespace = requireText(builder.historyNamespace, "historyNamespace");
        this.ownerChatterId = trimToNull(builder.ownerChatterId);
        this.teamId = trimToNull(builder.teamId);
        this.teamMemberId = trimToNull(builder.teamMemberId);
        this.sourceRobotName = trimToNull(builder.sourceRobotName);

        if (scope == Scope.TEAM) {
            if (teamId == null) {
                throw new IllegalArgumentException("TEAM identity 的 teamId 不能为空");
            }
            if (teamMemberId == null) {
                throw new IllegalArgumentException("TEAM identity 的 teamMemberId 不能为空");
            }
        } else if (teamId != null || teamMemberId != null) {
            throw new IllegalArgumentException("非 TEAM identity 不允许携带 teamId/teamMemberId");
        }
    }

    public static AcpClientIdentity main(String groupId, String historyNamespace,
                                         String sourceRobotName) {
        return builder(Scope.MAIN, groupId, groupId, historyNamespace)
                .sourceRobotName(sourceRobotName)
                .build();
    }

    public static AcpClientIdentity team(String logicalId, String transportGroup,
                                         String historyNamespace, String ownerChatterId,
                                         String teamId, String teamMemberId,
                                         String sourceRobotName) {
        return builder(Scope.TEAM, logicalId, transportGroup, historyNamespace)
                .ownerChatterId(ownerChatterId)
                .teamId(teamId)
                .teamMemberId(teamMemberId)
                .sourceRobotName(sourceRobotName)
                .build();
    }

    public static Builder builder(Scope scope, String logicalId, String transportGroup,
                                  String historyNamespace) {
        return new Builder(scope, logicalId, transportGroup, historyNamespace);
    }

    public Scope getScope() {
        return scope;
    }

    public String getLogicalId() {
        return logicalId;
    }

    public String getTransportGroup() {
        return transportGroup;
    }

    public String getHistoryNamespace() {
        return historyNamespace;
    }

    public String getOwnerChatterId() {
        return ownerChatterId;
    }

    public String getTeamId() {
        return teamId;
    }

    public String getTeamMemberId() {
        return teamMemberId;
    }

    public String getSourceRobotName() {
        return sourceRobotName;
    }

    public boolean isTeam() {
        return scope == Scope.TEAM;
    }

    private static String requireText(String value, String field) {
        String result = trimToNull(value);
        if (result == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return result;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class Builder {
        private final Scope scope;
        private final String logicalId;
        private final String transportGroup;
        private final String historyNamespace;
        private String ownerChatterId;
        private String teamId;
        private String teamMemberId;
        private String sourceRobotName;

        private Builder(Scope scope, String logicalId, String transportGroup,
                        String historyNamespace) {
            this.scope = scope;
            this.logicalId = logicalId;
            this.transportGroup = transportGroup;
            this.historyNamespace = historyNamespace;
        }

        public Builder ownerChatterId(String ownerChatterId) {
            this.ownerChatterId = ownerChatterId;
            return this;
        }

        public Builder teamId(String teamId) {
            this.teamId = teamId;
            return this;
        }

        public Builder teamMemberId(String teamMemberId) {
            this.teamMemberId = teamMemberId;
            return this;
        }

        public Builder sourceRobotName(String sourceRobotName) {
            this.sourceRobotName = sourceRobotName;
            return this;
        }

        public AcpClientIdentity build() {
            return new AcpClientIdentity(this);
        }
    }
}
