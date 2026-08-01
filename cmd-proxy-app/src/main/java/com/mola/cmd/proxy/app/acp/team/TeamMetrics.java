package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.team.model.TeamMetricsSnapshot;
import com.mola.cmd.proxy.app.acp.team.model.TeamResourceSnapshot;

import java.util.concurrent.atomic.AtomicLong;

final class TeamMetrics {

    private final AtomicLong createAccepted = new AtomicLong();
    private final AtomicLong createQuotaRejected = new AtomicLong();
    private final AtomicLong deleteCompleted = new AtomicLong();
    private final AtomicLong deleteWithWarnings = new AtomicLong();
    private final AtomicLong deleteFailed = new AtomicLong();
    private final AtomicLong recoveryStarted = new AtomicLong();
    private final AtomicLong reaperRuns = new AtomicLong();

    void createAccepted() {
        createAccepted.incrementAndGet();
    }

    void createQuotaRejected() {
        createQuotaRejected.incrementAndGet();
    }

    void deleteCompleted(boolean warnings) {
        deleteCompleted.incrementAndGet();
        if (warnings) deleteWithWarnings.incrementAndGet();
    }

    void deleteFailed() {
        deleteFailed.incrementAndGet();
    }

    void recoveryStarted() {
        recoveryStarted.incrementAndGet();
    }

    void reaperRun() {
        reaperRuns.incrementAndGet();
    }

    TeamMetricsSnapshot snapshot(int activeTeams, int activeMembers,
                                 TeamResourceSnapshot resources) {
        return new TeamMetricsSnapshot(
                createAccepted.get(), createQuotaRejected.get(),
                deleteCompleted.get(), deleteWithWarnings.get(),
                deleteFailed.get(), recoveryStarted.get(), reaperRuns.get(),
                activeTeams, activeMembers, resources);
    }
}
