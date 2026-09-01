package com.mola.cmd.proxy.app.acp.starweave;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Tracks the structured UI turn independently from provider request IDs. */
final class StarweaveTurnTracker {
    private final AtomicReference<String> current = new AtomicReference<>();
    private final AtomicReference<String> pending = new AtomicReference<>();

    String begin() {
        String turnId = UUID.randomUUID().toString();
        current.set(turnId);
        return turnId;
    }

    String beginPending() {
        String turnId = UUID.randomUUID().toString();
        pending.set(turnId);
        return turnId;
    }

    String currentOrBegin() {
        String existing = current.get();
        if (existing != null) return existing;
        String created = UUID.randomUUID().toString();
        return current.compareAndSet(null, created) ? created : current.get();
    }

    String complete() {
        String turnId = currentOrBegin();
        if (current.compareAndSet(turnId, null)) {
            String next = pending.getAndSet(null);
            if (next != null) current.compareAndSet(null, next);
        }
        return turnId;
    }

    void clear(String expected) {
        if (expected != null) {
            current.compareAndSet(expected, null);
            pending.compareAndSet(expected, null);
        }
    }

    void reset() {
        current.set(null);
        pending.set(null);
    }
}
