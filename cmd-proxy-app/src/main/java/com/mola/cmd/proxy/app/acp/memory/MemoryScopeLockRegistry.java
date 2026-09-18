package com.mola.cmd.proxy.app.acp.memory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 以最终存储目录为粒度共享 Memory 读改写锁。
 *
 * <p>manager 身份不能作为锁键：普通、Team 和 sub-agent manager 可能指向
 * 同一个 workspace/scope 目录。使用规范化落盘路径可以覆盖这些别名。</p>
 */
public final class MemoryScopeLockRegistry {

    private static final ConcurrentHashMap<String, ReentrantLock> STORAGE_LOCKS = new ConcurrentHashMap<>();

    public static ReentrantLock lockForStoragePath(java.nio.file.Path path) {
        return STORAGE_LOCKS.computeIfAbsent(path.toAbsolutePath().normalize().toString(),
                ignored -> new ReentrantLock(true));
    }

    private final ConcurrentHashMap<String, ReentrantLock> locks =
            new ConcurrentHashMap<>();

    public ReentrantLock lockFor(MemoryFileStore fileStore,
                                 String workspacePath) {
        String storageKey = fileStore.getStorageKey(workspacePath);
        return locks.computeIfAbsent(storageKey,
                ignored -> lockForStoragePath(java.nio.file.Paths.get(storageKey)));
    }

    int size() {
        return locks.size();
    }
}
