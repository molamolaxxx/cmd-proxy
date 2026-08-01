package com.mola.cmd.proxy.app.acp.team.model;

public final class TeamMetricsSnapshot {

    private final long createAccepted;
    private final long createQuotaRejected;
    private final long deleteCompleted;
    private final long deleteWithWarnings;
    private final long deleteFailed;
    private final long recoveryStarted;
    private final long reaperRuns;
    private final int activeTeams;
    private final int activeMembers;
    private final TeamResourceSnapshot resources;

    public TeamMetricsSnapshot(long createAccepted, long createQuotaRejected,
                               long deleteCompleted, long deleteWithWarnings,
                               long deleteFailed, long recoveryStarted,
                               long reaperRuns, int activeTeams,
                               int activeMembers,
                               TeamResourceSnapshot resources) {
        this.createAccepted = createAccepted;
        this.createQuotaRejected = createQuotaRejected;
        this.deleteCompleted = deleteCompleted;
        this.deleteWithWarnings = deleteWithWarnings;
        this.deleteFailed = deleteFailed;
        this.recoveryStarted = recoveryStarted;
        this.reaperRuns = reaperRuns;
        this.activeTeams = activeTeams;
        this.activeMembers = activeMembers;
        this.resources = resources;
    }

    public long getCreateAccepted() {
        return createAccepted;
    }

    public long getCreateQuotaRejected() {
        return createQuotaRejected;
    }

    public long getDeleteCompleted() {
        return deleteCompleted;
    }

    public long getDeleteWithWarnings() {
        return deleteWithWarnings;
    }

    public long getDeleteFailed() {
        return deleteFailed;
    }

    public long getRecoveryStarted() {
        return recoveryStarted;
    }

    public long getReaperRuns() {
        return reaperRuns;
    }

    public int getActiveTeams() {
        return activeTeams;
    }

    public int getActiveMembers() {
        return activeMembers;
    }

    public TeamResourceSnapshot getResources() {
        return resources;
    }
}
