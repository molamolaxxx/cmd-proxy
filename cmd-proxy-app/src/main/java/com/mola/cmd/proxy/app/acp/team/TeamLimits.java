package com.mola.cmd.proxy.app.acp.team;

/**
 * Fast Team 实例级配额。环境变量优先，系统属性次之，非法值回退默认值。
 */
public final class TeamLimits {

    public static final String ENV_MAX_ACTIVE_TEAMS =
            "CMD_PROXY_TEAM_MAX_ACTIVE_TEAMS";
    public static final String PROP_MAX_ACTIVE_TEAMS =
            "cmd.proxy.team.maxActiveTeams";
    public static final String ENV_MAX_MEMBERS_PER_TEAM =
            "CMD_PROXY_TEAM_MAX_MEMBERS_PER_TEAM";
    public static final String PROP_MAX_MEMBERS_PER_TEAM =
            "cmd.proxy.team.maxMembersPerTeam";
    public static final String ENV_MAX_TOTAL_MEMBERS =
            "CMD_PROXY_TEAM_MAX_TOTAL_MEMBERS";
    public static final String PROP_MAX_TOTAL_MEMBERS =
            "cmd.proxy.team.maxTotalMembers";

    private final int maxActiveTeams;
    private final int maxMembersPerTeam;
    private final int maxTotalMembers;

    public TeamLimits(int maxActiveTeams, int maxMembersPerTeam,
                      int maxTotalMembers) {
        if (maxActiveTeams < 1) {
            throw new IllegalArgumentException("maxActiveTeams must be positive");
        }
        if (maxMembersPerTeam < 1 || maxMembersPerTeam > 6) {
            throw new IllegalArgumentException(
                    "maxMembersPerTeam must be between 1 and 6");
        }
        if (maxTotalMembers < maxMembersPerTeam) {
            throw new IllegalArgumentException(
                    "maxTotalMembers must be at least maxMembersPerTeam");
        }
        this.maxActiveTeams = maxActiveTeams;
        this.maxMembersPerTeam = maxMembersPerTeam;
        this.maxTotalMembers = maxTotalMembers;
    }

    public static TeamLimits system() {
        int membersPerTeam = bounded(ENV_MAX_MEMBERS_PER_TEAM,
                PROP_MAX_MEMBERS_PER_TEAM, 6, 1, 6);
        return new TeamLimits(
                positive(ENV_MAX_ACTIVE_TEAMS, PROP_MAX_ACTIVE_TEAMS, 20),
                membersPerTeam,
                Math.max(membersPerTeam, positive(ENV_MAX_TOTAL_MEMBERS,
                        PROP_MAX_TOTAL_MEMBERS, 100)));
    }

    public int getMaxActiveTeams() {
        return maxActiveTeams;
    }

    public int getMaxMembersPerTeam() {
        return maxMembersPerTeam;
    }

    public int getMaxTotalMembers() {
        return maxTotalMembers;
    }

    private static int positive(String env, String property, int fallback) {
        return bounded(env, property, fallback, 1, Integer.MAX_VALUE);
    }

    private static int bounded(String env, String property, int fallback,
                               int min, int max) {
        String value = firstNonBlank(
                System.getenv(env), System.getProperty(property));
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= min && parsed <= max ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return null;
    }
}
