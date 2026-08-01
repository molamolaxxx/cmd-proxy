package com.mola.cmd.proxy.app.acp.team.runtime;

import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Team 的最小内存生命周期容器。业务执行器和成员 runtime 在后续阶段装配。
 */
public final class TeamRuntime {

    private final AtomicReference<TeamDefinition> definition;
    private final ReentrantLock operationLock = new ReentrantLock();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong eventSequencer = new AtomicLong();
    private final AtomicBoolean acceptingRequests = new AtomicBoolean(true);
    private final long createdAt;
    private final AtomicLong lastActiveAt;

    public TeamRuntime(TeamDefinition definition) {
        this.definition = new AtomicReference<>(Objects.requireNonNull(definition, "definition"));
        this.createdAt = definition.getCreatedAt();
        this.lastActiveAt = new AtomicLong(System.currentTimeMillis());
        if (definition.getState().isTerminal()) {
            acceptingRequests.set(false);
        }
    }

    public TeamDefinition getDefinition() {
        return definition.get();
    }

    public boolean publishNextDefinition(TeamDefinition next) {
        Objects.requireNonNull(next, "next");
        while (true) {
            TeamDefinition current = definition.get();
            if (current.getState().isTerminal()
                    || !current.getTeamId().equals(next.getTeamId())
                    || next.getVersion() != current.getVersion() + 1L) {
                return false;
            }
            if (definition.compareAndSet(current, next)) {
                lastActiveAt.set(System.currentTimeMillis());
                if (next.getState().isTerminal()) {
                    acceptingRequests.set(false);
                    generation.incrementAndGet();
                }
                return true;
            }
        }
    }

    public long stopAcceptingRequests() {
        acceptingRequests.set(false);
        return generation.incrementAndGet();
    }

    public boolean isAcceptingRequests() {
        return acceptingRequests.get();
    }

    public long getGeneration() {
        return generation.get();
    }

    public long nextEventSeq() {
        return eventSequencer.incrementAndGet();
    }

    public long getLatestEventSeq() {
        return eventSequencer.get();
    }

    public ReentrantLock getOperationLock() {
        return operationLock;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastActiveAt() {
        return lastActiveAt.get();
    }
}
