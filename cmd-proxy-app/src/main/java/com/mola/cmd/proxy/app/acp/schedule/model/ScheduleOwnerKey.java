package com.mola.cmd.proxy.app.acp.schedule.model;

import java.util.Objects;

/**
 * 定时任务的显式 owner 身份。
 *
 * <p>MAIN 保持历史目录 {@code schedules/{robotName}/tasks.json}；
 * TEAM 使用 {@code schedules/team/{teamId}/{teamMemberId}/tasks.json}。</p>
 */
public final class ScheduleOwnerKey {

    public enum Scope {
        MAIN,
        TEAM
    }

    private final Scope scope;
    private final String ownerId;
    private final String robotName;
    private final String teamId;
    private final String teamMemberId;
    private final String persistencePath;

    private ScheduleOwnerKey(Scope scope, String ownerId, String robotName,
                             String teamId, String teamMemberId,
                             String persistencePath) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.ownerId = requireText(ownerId, "ownerId");
        this.robotName = trimToNull(robotName);
        this.teamId = trimToNull(teamId);
        this.teamMemberId = trimToNull(teamMemberId);
        this.persistencePath = requireSafeRelativePath(persistencePath);
        if (scope == Scope.TEAM) {
            requireSafeId(this.teamId, "teamId");
            requireSafeId(this.teamMemberId, "teamMemberId");
        } else if (this.teamId != null || this.teamMemberId != null) {
            throw new IllegalArgumentException(
                    "MAIN schedule owner cannot carry Team identity");
        }
    }

    public static ScheduleOwnerKey main(String robotName) {
        String name = requireText(robotName, "robotName");
        if (name.contains("/") || name.contains("\\")
                || ".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException(
                    "MAIN robotName is not persistence-safe");
        }
        return new ScheduleOwnerKey(
                Scope.MAIN, name, name, null, null, name);
    }

    public static ScheduleOwnerKey team(String ownerChatterId, String teamId,
                                        String teamMemberId, String robotName) {
        String safeTeam = requireSafeId(teamId, "teamId");
        String safeMember = requireSafeId(teamMemberId, "teamMemberId");
        return new ScheduleOwnerKey(
                Scope.TEAM, requireText(ownerChatterId, "ownerChatterId"),
                robotName, safeTeam, safeMember,
                "team/" + safeTeam + "/" + safeMember);
    }

    public static ScheduleOwnerKey fromPersistencePath(String path) {
        String normalized = requireSafeRelativePath(path);
        String[] parts = normalized.split("/");
        if (parts.length == 3 && "team".equals(parts[0])) {
            return team("restored", parts[1], parts[2], null);
        }
        if (parts.length == 1) {
            return main(parts[0]);
        }
        throw new IllegalArgumentException("unknown schedule persistence path");
    }

    public Scope getScope() {
        return scope;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getRobotName() {
        return robotName;
    }

    public String getTeamId() {
        return teamId;
    }

    public String getTeamMemberId() {
        return teamMemberId;
    }

    public String getPersistencePath() {
        return persistencePath;
    }

    public boolean isTeam() {
        return scope == Scope.TEAM;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof ScheduleOwnerKey)) return false;
        ScheduleOwnerKey other = (ScheduleOwnerKey) value;
        return persistencePath.equals(other.persistencePath);
    }

    @Override
    public int hashCode() {
        return persistencePath.hashCode();
    }

    @Override
    public String toString() {
        return scope + ":" + persistencePath;
    }

    private static String requireSafeRelativePath(String value) {
        String path = requireText(value, "persistencePath")
                .replace('\\', '/');
        if (path.startsWith("/") || path.endsWith("/") || path.contains("//")) {
            throw new IllegalArgumentException("persistencePath is not relative-safe");
        }
        for (String part : path.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException(
                        "persistencePath contains unsafe segment");
            }
        }
        return path;
    }

    private static String requireSafeId(String value, String field) {
        String text = requireText(value, field);
        if (!text.matches("[a-zA-Z0-9._-]+")
                || ".".equals(text) || "..".equals(text)) {
            throw new IllegalArgumentException(field + " is not safe");
        }
        return text;
    }

    private static String requireText(String value, String field) {
        String text = trimToNull(value);
        if (text == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return text;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
