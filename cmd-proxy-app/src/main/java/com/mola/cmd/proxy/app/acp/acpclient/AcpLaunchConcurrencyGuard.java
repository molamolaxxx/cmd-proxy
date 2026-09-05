package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes provider startup for clients that share provider bootstrap state in one workspace.
 *
 * <p>The guard is intentionally below Main, Team, sub-agent, memory, and ability-reflection
 * entrypoints so every {@link AbstractAcpClient} startup follows the same rule. A lease is held
 * only through ACP initialization and session creation, never for the process lifetime.</p>
 */
final class AcpLaunchConcurrencyGuard {

    private static final Logger logger =
            LoggerFactory.getLogger(AcpLaunchConcurrencyGuard.class);
    private static final ConcurrentHashMap<String, LockSlot> SLOTS =
            new ConcurrentHashMap<>();
    private static final Lease NOOP_LEASE = new Lease(null, null);

    private AcpLaunchConcurrencyGuard() {
    }

    static Lease acquire(AgentProvider provider, String workspacePath,
                         String logicalId) throws IOException {
        if (provider == null || !provider.requiresSerializedWorkspaceLaunch()) {
            return NOOP_LEASE;
        }

        String workspaceKey = canonicalWorkspaceKey(workspacePath);
        String key = provider.getName() + "\n" + workspaceKey;
        LockSlot slot = reserve(key);
        long waitStartedNanos = System.nanoTime();
        boolean acquired = false;
        try {
            // The timed form honors the fairness policy, unlike the immediate tryLock().
            acquired = slot.lock.tryLock(0L, TimeUnit.NANOSECONDS);
            if (!acquired) {
                logger.info("ACP 启动排队, provider={}, workspace={}, logicalId={}",
                        provider.getName(), workspaceKey, logicalId);
                slot.lock.lockInterruptibly();
                acquired = true;
                long waitedMillis = TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - waitStartedNanos);
                logger.info("ACP 获得启动锁, provider={}, workspace={}, logicalId={}, waitedMs={}",
                        provider.getName(), workspaceKey, logicalId, waitedMillis);
            }
            return new Lease(key, slot);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待 " + provider.getName()
                    + " ACP 启动锁时被中断, workspace=" + workspaceKey, e);
        } finally {
            if (!acquired) {
                releaseReservation(key, slot);
            }
        }
    }

    private static LockSlot reserve(String key) {
        return SLOTS.compute(key, (ignored, current) -> {
            LockSlot slot = current == null ? new LockSlot() : current;
            slot.references++;
            return slot;
        });
    }

    private static void releaseReservation(String key, LockSlot expected) {
        SLOTS.computeIfPresent(key, (ignored, current) -> {
            if (current != expected) {
                return current;
            }
            current.references--;
            return current.references == 0 ? null : current;
        });
    }

    private static String canonicalWorkspaceKey(String workspacePath) {
        String effective = workspacePath == null || workspacePath.trim().isEmpty()
                ? System.getProperty("user.home") : workspacePath;
        try {
            Path absolute = Paths.get(effective).toAbsolutePath().normalize();
            try {
                return absolute.toRealPath().toString();
            } catch (IOException ignored) {
                return absolute.toString();
            }
        } catch (RuntimeException invalidPath) {
            // Preserve the pre-existing startup behavior for unusual provider paths. The provider
            // will still receive and validate the original value during session creation.
            return effective.trim();
        }
    }

    private static final class LockSlot {
        private final ReentrantLock lock = new ReentrantLock(true);
        /** Guarded by the map's per-key compute operation. Includes holders and waiters. */
        private int references;
    }

    static final class Lease implements AutoCloseable {
        private final String key;
        private final LockSlot slot;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(String key, LockSlot slot) {
            this.key = key;
            this.slot = slot;
        }

        @Override
        public void close() {
            if (slot == null || !closed.compareAndSet(false, true)) {
                return;
            }
            try {
                slot.lock.unlock();
            } finally {
                releaseReservation(key, slot);
            }
        }
    }
}
