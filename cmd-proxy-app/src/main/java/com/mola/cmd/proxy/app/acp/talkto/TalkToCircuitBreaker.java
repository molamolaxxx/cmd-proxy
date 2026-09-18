package com.mola.cmd.proxy.app.acp.talkto;

import com.mola.cmd.proxy.app.acp.talkto.model.TalkToTrace;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded, server-owned admission control for causal TalkTo cascades. */
public final class TalkToCircuitBreaker {
    public static final int MAX_HOPS = 5;
    public static final int MAX_MESSAGES = 12;
    public static final int MAX_MESSAGES_PER_SENDER = 5;
    public static final int MAX_MESSAGES_PER_EDGE = 5;
    public static final long CASCADE_TTL_MS = 2L * 60L * 60L * 1000L;

    private final ConcurrentHashMap<String, CascadeState> cascades =
            new ConcurrentHashMap<>();

    public synchronized Admission admit(TalkToTrace parent, String sender, String target) {
        long now = System.currentTimeMillis();
        cleanup(now);
        TalkToTrace trace = parent == null ? TalkToTrace.root() : parent.next();
        CascadeState state = stateFor(trace);
        synchronized (state) {
            if (state.open) return Admission.rejected(trace, state.reason, true);
            if (state.startedAt > now + 60_000L) {
                return open(state, trace, "INVALID_STARTED_AT");
            }
            if (now - state.startedAt >= CASCADE_TTL_MS) {
                return open(state, trace, "CASCADE_EXPIRED");
            }
            if (trace.getHopCount() > MAX_HOPS) {
                return open(state, trace, "HOP_LIMIT");
            }
            if (state.messages >= MAX_MESSAGES) {
                return open(state, trace, "MESSAGE_LIMIT");
            }
            int senderCount = count(state.senderCounts, sender);
            if (senderCount >= MAX_MESSAGES_PER_SENDER) {
                return Admission.rejected(trace, "SENDER_LIMIT", false,
                        senderCount, MAX_MESSAGES_PER_SENDER);
            }
            String edge = clean(sender) + "→" + clean(target);
            int edgeCount = count(state.edgeCounts, edge);
            if (edgeCount >= MAX_MESSAGES_PER_EDGE) {
                return Admission.rejected(trace, "EDGE_LIMIT", false,
                        edgeCount, MAX_MESSAGES_PER_EDGE);
            }
            state.messages++;
            state.senderCounts.put(clean(sender), senderCount + 1);
            state.edgeCounts.put(edge, edgeCount + 1);
            return Admission.accepted(trace, state.messages);
        }
    }

    public synchronized void rollback(Admission admission, String sender, String target) {
        if (admission == null || !admission.accepted
                || !admission.rolledBack.compareAndSet(false, true)) return;
        CascadeState state = cascades.get(admission.trace.getCascadeId());
        if (state == null) return;
        synchronized (state) {
            if (state.open) return;
            if (state.messages > 0) state.messages--;
            decrement(state.senderCounts, clean(sender));
            decrement(state.edgeCounts, clean(sender) + "→" + clean(target));
            // Keep aliases until TTL cleanup; removing just one alias could split a live budget.
        }
    }

    public synchronized int activeCascadeCount() {
        cleanup(System.currentTimeMillis());
        return (int) cascades.values().stream().distinct().filter(s -> s.messages > 0 || s.open).count();
    }

    /** Recheck at consumption time: queued work must not restart an opened cascade. */
    public synchronized boolean canDeliver(TalkToTrace trace) {
        if (trace == null) return false;
        long now = System.currentTimeMillis();
        if (trace.getHopCount() > MAX_HOPS
                || trace.getStartedAt() > now + 60_000L
                || now - trace.getStartedAt() >= CASCADE_TTL_MS) return false;
        CascadeState state = stateFor(trace);
        synchronized (state) { return !state.open; }
    }

    /** Exactly one termination projection per cascade in this dispatcher. */
    public synchronized boolean claimNotification(String cascadeId) {
        CascadeState state = cascades.get(cascadeId);
        if (state == null) return false;
        synchronized (state) {
            if (!state.open || state.notified) return false;
            state.notified = true;
            return true;
        }
    }

    public synchronized boolean claimRelay(String cascadeId) {
        CascadeState state = cascades.get(cascadeId);
        if (state == null || state.relayed) return false;
        state.relayed = true;
        return true;
    }

    public synchronized void clear() {
        cascades.clear();
    }

    /** Trusted coordinator control; this does not admit a message or increment a hop. */
    public synchronized Admission openCascade(TalkToTrace trace, String reason) {
        cleanup(System.currentTimeMillis());
        CascadeState state = stateFor(trace);
        synchronized (state) {
            return state.open ? Admission.rejected(trace, state.reason, true)
                    : open(state, trace, reason);
        }
    }

    /** Union aliases under the dispatcher lock, retaining all prior charges and open flags. */
    private CascadeState stateFor(TalkToTrace trace) {
        CascadeState combined = null;
        Set<CascadeState> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String id : trace.getCascadeIds()) {
            CascadeState existing = cascades.get(id);
            if (existing == null || !seen.add(existing)) continue;
            if (combined == null) { combined = existing; continue; }
            combined.startedAt = Math.min(combined.startedAt, existing.startedAt);
            combined.messages += existing.messages;
            final CascadeState mergeTarget = combined;
            existing.senderCounts.forEach((key, count) -> combinedCount(mergeTarget.senderCounts, key, count));
            existing.edgeCounts.forEach((key, count) -> combinedCount(mergeTarget.edgeCounts, key, count));
            if (existing.open) { combined.open = true; combined.reason = existing.reason; }
            combined.notified |= existing.notified;
            combined.relayed |= existing.relayed;
        }
        if (combined == null) combined = new CascadeState(trace.getStartedAt());
        combined.startedAt = Math.min(combined.startedAt, trace.getStartedAt());
        final CascadeState destination = combined;
        if (seen.size() > 1) cascades.replaceAll((key, value) -> seen.contains(value) ? destination : value);
        for (String id : trace.getCascadeIds()) cascades.put(id, destination);
        return destination;
    }

    private static void combinedCount(Map<String, Integer> counts, String key, int count) {
        counts.put(key, counts.getOrDefault(key, 0) + count);
    }

    private Admission open(CascadeState state, TalkToTrace trace, String reason) {
        state.open = true;
        state.reason = reason;
        return Admission.rejected(trace, reason, true);
    }

    private void cleanup(long now) {
        cascades.entrySet().removeIf(entry ->
                now - entry.getValue().startedAt >= CASCADE_TTL_MS * 2);
    }

    private static int count(Map<String, Integer> values, String key) {
        Integer value = values.get(clean(key));
        return value == null ? 0 : value;
    }

    private static void decrement(Map<String, Integer> values, String key) {
        Integer current = values.get(key);
        if (current == null) return;
        if (current <= 1) values.remove(key); else values.put(key, current - 1);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class CascadeState {
        private long startedAt;
        private final Map<String, Integer> senderCounts = new HashMap<>();
        private final Map<String, Integer> edgeCounts = new HashMap<>();
        private int messages;
        private boolean open;
        private boolean notified;
        private boolean relayed;
        private String reason;

        private CascadeState(long startedAt) {
            this.startedAt = startedAt;
        }
    }

    public static final class Admission {
        private final boolean accepted;
        private final TalkToTrace trace;
        private final String reason;
        private final int messageCount;
        private final boolean circuitOpen;
        private final int current;
        private final int limit;
        private final java.util.concurrent.atomic.AtomicBoolean rolledBack =
                new java.util.concurrent.atomic.AtomicBoolean();

        private Admission(boolean accepted, TalkToTrace trace, String reason,
                          int messageCount, boolean circuitOpen, int current, int limit) {
            this.accepted = accepted;
            this.trace = trace;
            this.reason = reason;
            this.messageCount = messageCount;
            this.circuitOpen = circuitOpen;
            this.current = current;
            this.limit = limit;
        }

        private static Admission accepted(TalkToTrace trace, int count) {
            return new Admission(true, trace, null, count, false, count, MAX_MESSAGES);
        }

        private static Admission rejected(TalkToTrace trace, String reason,
                                          boolean circuitOpen) {
            return new Admission(false, trace, reason, 0, circuitOpen, -1, -1);
        }

        private static Admission rejected(TalkToTrace trace, String reason,
                                          boolean circuitOpen, int current, int limit) {
            return new Admission(false, trace, reason, 0, circuitOpen, current, limit);
        }

        public boolean isAccepted() { return accepted; }
        public TalkToTrace getTrace() { return trace; }
        public String getReason() { return reason; }
        public int getMessageCount() { return messageCount; }
        public boolean isCircuitOpen() { return circuitOpen; }
        public int getCurrent() { return current; }
        public int getLimit() { return limit; }
    }
}
