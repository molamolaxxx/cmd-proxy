package com.mola.cmd.proxy.app.acp.team.model;

public final class TeamResourceSnapshot {

    private final int runtimes;
    private final int clients;
    private final int talkToDispatchers;
    private final int pendingCleanup;
    private final int scheduleOwners;

    public TeamResourceSnapshot(int runtimes, int clients,
                                int talkToDispatchers, int pendingCleanup,
                                int scheduleOwners) {
        this.runtimes = runtimes;
        this.clients = clients;
        this.talkToDispatchers = talkToDispatchers;
        this.pendingCleanup = pendingCleanup;
        this.scheduleOwners = scheduleOwners;
    }

    public int getRuntimes() {
        return runtimes;
    }

    public int getClients() {
        return clients;
    }

    public int getTalkToDispatchers() {
        return talkToDispatchers;
    }

    public int getPendingCleanup() {
        return pendingCleanup;
    }

    public int getScheduleOwners() {
        return scheduleOwners;
    }

    public boolean isZero() {
        return runtimes == 0 && clients == 0
                && talkToDispatchers == 0 && pendingCleanup == 0
                && scheduleOwners == 0;
    }
}
