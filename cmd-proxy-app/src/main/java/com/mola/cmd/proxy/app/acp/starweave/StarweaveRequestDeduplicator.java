package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSONObject;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/** Bounded in-process idempotence window for Starweave mutation requests. */
public final class StarweaveRequestDeduplicator {
    private static final int DEFAULT_CAPACITY = 2048;
    private static final long DEFAULT_TTL_MILLIS = 10 * 60 * 1000L;

    private final int capacity;
    private final long ttlMillis;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();

    public StarweaveRequestDeduplicator() {
        this(DEFAULT_CAPACITY, DEFAULT_TTL_MILLIS);
    }

    StarweaveRequestDeduplicator(int capacity, long ttlMillis) {
        if (capacity < 1 || ttlMillis < 1L) {
            throw new IllegalArgumentException("capacity and ttlMillis must be positive");
        }
        this.capacity = capacity;
        this.ttlMillis = ttlMillis;
    }

    public JSONObject execute(String requestId, String fingerprint,
                              Callable<JSONObject> command) throws Exception {
        if (requestId == null || requestId.trim().isEmpty()) return command.call();
        String key = requestId.trim();
        Entry entry;
        boolean owner = false;
        synchronized (admissionLock) {
            long now = System.currentTimeMillis();
            cleanup(now);
            entry = entries.get(key);
            if (entry == null) {
                if (entries.size() >= capacity) {
                    throw new IllegalStateException(
                            "too many active idempotent Starweave requests");
                }
                entry = new Entry(fingerprintHash(fingerprint), now);
                entries.put(key, entry);
                owner = true;
            }
        }
        if (owner) {
            try {
                JSONObject result = command.call();
                entry.result.complete(result);
                return result;
            } catch (Exception e) {
                entry.result.completeExceptionally(e);
                entries.remove(key, entry);
                throw e;
            }
        }
        if (!entry.fingerprint.equals(fingerprintHash(fingerprint))) {
            throw new IllegalArgumentException(
                    "requestId has already been used with a different command");
        }
        try {
            return entry.result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting duplicate request", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("duplicate request failed", cause);
        }
    }

    private static String fingerprintHash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void cleanup(long now) {
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            Entry entry = item.getValue();
            if (entry.result.isDone() && now - entry.createdAt > ttlMillis) {
                entries.remove(item.getKey(), entry);
            }
        }
        if (entries.size() < capacity) return;
        Entry oldest = null;
        String oldestKey = null;
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            Entry entry = item.getValue();
            if (!entry.result.isDone()) continue;
            if (oldest == null || entry.createdAt < oldest.createdAt) {
                oldest = entry;
                oldestKey = item.getKey();
            }
        }
        if (oldestKey != null) entries.remove(oldestKey, oldest);
    }

    private static final class Entry {
        private final String fingerprint;
        private final long createdAt;
        private final CompletableFuture<JSONObject> result = new CompletableFuture<>();

        private Entry(String fingerprint, long createdAt) {
            this.fingerprint = fingerprint == null ? "" : fingerprint;
            this.createdAt = createdAt;
        }
    }
}
