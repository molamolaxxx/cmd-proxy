package com.mola.cmd.proxy.app.acp.memory;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一持有普通、Team 与 sub-agent 的 MemoryManager。
 *
 * <p>ownerKey 决定 manager/异步队列复用；所有 manager 仍共享同一存储锁注册表，
 * 因而不同 ownerKey 指向同一实际目录时也不会发生 stale-index 覆盖。</p>
 */
public final class MemoryManagerRegistry {

    private final ConcurrentHashMap<String, MemoryManager> managers =
            new ConcurrentHashMap<>();
    private final java.util.Set<MemoryManager> retiredManagers =
            ConcurrentHashMap.newKeySet();
    private final MemoryScopeLockRegistry locks = new MemoryScopeLockRegistry();

    public MemoryManager getOrCreate(String ownerKey, MemoryConfig config,
                                     AcpRobotParam robotParam) {
        return managers.computeIfAbsent(ownerKey,
                ignored -> new MemoryManager(config, robotParam, locks));
    }

    public Optional<MemoryManager> get(String ownerKey) {
        return Optional.ofNullable(managers.get(ownerKey));
    }

    public void remove(String ownerKey) {
        MemoryManager removed = managers.remove(ownerKey);
        if (removed != null) {
            // Team client 可能仍引用来源 manager，延迟到全局 stop 再关闭队列。
            retiredManagers.add(removed);
        }
    }

    public int size() {
        return managers.size();
    }

    public MemoryScopeLockRegistry getLockRegistry() {
        return locks;
    }

    public void shutdownAll() {
        shutdownAll(false);
    }

    /** 进程 stop 使用：保留 pending 标记并立即取消模型队列。 */
    public void shutdownAllNow() {
        shutdownAll(true);
    }

    private void shutdownAll(boolean immediate) {
        LinkedHashSet<MemoryManager> unique =
                new LinkedHashSet<>(new ArrayList<>(managers.values()));
        unique.addAll(retiredManagers);
        managers.clear();
        retiredManagers.clear();
        for (MemoryManager manager : unique) {
            if (immediate) {
                manager.shutdownNow();
            } else {
                manager.shutdown();
            }
        }
    }
}
