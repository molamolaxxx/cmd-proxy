package com.mola.cmd.proxy.app.acp.team;

public final class TeamMemberStartOptions {

    private final boolean forceNewSession;
    private final String targetRestoreSessionId;

    private TeamMemberStartOptions(boolean forceNewSession, String targetRestoreSessionId) {
        this.forceNewSession = forceNewSession;
        this.targetRestoreSessionId = targetRestoreSessionId;
    }

    public static TeamMemberStartOptions initial() {
        return new TeamMemberStartOptions(false, null);
    }

    public static TeamMemberStartOptions newSession() {
        return new TeamMemberStartOptions(true, null);
    }

    public static TeamMemberStartOptions restore(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return new TeamMemberStartOptions(false, sessionId.trim());
    }

    public boolean isForceNewSession() {
        return forceNewSession;
    }

    public String getTargetRestoreSessionId() {
        return targetRestoreSessionId;
    }
}
