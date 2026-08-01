package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;

import java.util.Objects;

/**
 * 普通与 Team AcpClient 共用的能力挂点编排器。
 *
 * <p>Team 专项隔离能力通过各 Hook 的 {@link Context} 分支扩展；初始化器不会静默跳过
 * memory、schedule 等正常能力。</p>
 */
public final class AcpClientFeatureInitializer {

    @FunctionalInterface
    public interface Hook {
        void initialize(Context context, AcpClient client, AcpRobotParam robot)
                throws Exception;
    }

    public enum Scope {
        MAIN,
        TEAM
    }

    public static final class Context {
        private final Scope scope;
        private final String featureOwnerKey;
        private final String sourceGroupId;
        private final String teamId;
        private final String teamMemberId;

        private Context(Scope scope, String featureOwnerKey, String sourceGroupId,
                        String teamId, String teamMemberId) {
            this.scope = Objects.requireNonNull(scope, "scope");
            this.featureOwnerKey = requireText(featureOwnerKey, "featureOwnerKey");
            this.sourceGroupId = requireText(sourceGroupId, "sourceGroupId");
            this.teamId = teamId;
            this.teamMemberId = teamMemberId;
        }

        public static Context main(String groupId) {
            return new Context(Scope.MAIN, groupId, groupId, null, null);
        }

        public static Context team(String acpClientId, String sourceGroupId,
                                   String teamId, String teamMemberId) {
            return new Context(Scope.TEAM, acpClientId, sourceGroupId,
                    requireText(teamId, "teamId"),
                    requireText(teamMemberId, "teamMemberId"));
        }

        public Scope getScope() {
            return scope;
        }

        public String getFeatureOwnerKey() {
            return featureOwnerKey;
        }

        public String getSourceGroupId() {
            return sourceGroupId;
        }

        public String getTeamId() {
            return teamId;
        }

        public String getTeamMemberId() {
            return teamMemberId;
        }
    }

    private final Hook memory;
    private final Hook ability;
    private final Hook subAgent;
    private final Hook schedule;
    private final Hook talkTo;

    public AcpClientFeatureInitializer(Hook memory, Hook ability, Hook subAgent,
                                       Hook schedule, Hook talkTo) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.ability = Objects.requireNonNull(ability, "ability");
        this.subAgent = Objects.requireNonNull(subAgent, "subAgent");
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        this.talkTo = Objects.requireNonNull(talkTo, "talkTo");
    }

    public void initialize(Context context, AcpClient client, AcpRobotParam robot)
            throws Exception {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(client, "client");
        memory.initialize(context, client, robot);
        ability.initialize(context, client, robot);
        subAgent.initialize(context, client, robot);
        schedule.initialize(context, client, robot);
        talkTo.initialize(context, client, robot);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
