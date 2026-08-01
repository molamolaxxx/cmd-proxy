package com.mola.cmd.proxy.app.acp.team;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fast Team 孤儿与 TTL 资源清理器。
 */
public final class TeamResourceReaper implements AutoCloseable {

    private static final Logger logger =
            LoggerFactory.getLogger(TeamResourceReaper.class);
    private final TeamManager manager;
    private final AtomicBoolean started = new AtomicBoolean();
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "team-resource-reaper");
                thread.setDaemon(true);
                return thread;
            });

    TeamResourceReaper(TeamManager manager) {
        this.manager = manager;
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(this::runSafely,
                    60L, 60L, TimeUnit.SECONDS);
        }
    }

    public void runOnce() {
        manager.reapResources(System.currentTimeMillis());
    }

    private void runSafely() {
        try {
            runOnce();
        } catch (Exception e) {
            logger.warn("Team resource reaper 执行失败", e);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
