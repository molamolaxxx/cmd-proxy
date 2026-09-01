package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Durable Starweave event journal with a bounded in-memory tail for live delivery.
 * Identifiers are hashed before becoming path components so callers cannot escape
 * the server-owned storage root.
 */
public final class StarweaveSessionEventStore {
    private static final Logger log = LoggerFactory.getLogger(StarweaveSessionEventStore.class);
    private static final int DEFAULT_CAPACITY = 2000;
    private static final int MAX_SNAPSHOT_EVENTS = 10_000;
    private static final long MAX_JOURNAL_BYTES = 16L * 1024L * 1024L;
    private static final String JOURNAL_FILE = "events.jsonl";

    private final int capacity;
    private final Path root;
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final Map<String, Deque<StarweaveSessionEvent>> events = new ConcurrentHashMap<>();
    private final Map<String, Object> groupLocks = new ConcurrentHashMap<>();
    private final Map<String, Boolean> loadedGroups = new ConcurrentHashMap<>();

    public StarweaveSessionEventStore() {
        this(DEFAULT_CAPACITY, CmdProxyHome.resolve("starweave/events"));
    }

    /** In-memory constructor retained for focused unit tests. */
    StarweaveSessionEventStore(int capacity) {
        this(capacity, null);
    }

    StarweaveSessionEventStore(int capacity, Path root) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.root = root == null ? null : root.toAbsolutePath().normalize();
    }

    public StarweaveSessionEvent append(String groupId, String sessionId,
                                        long generation, String type,
                                        JSONObject payload) {
        return append(groupId, sessionId, null, generation, type, payload);
    }

    public StarweaveSessionEvent append(String groupId, String sessionId,
                                        String turnId, long generation, String type,
                                        JSONObject payload) {
        requireIdentifier(groupId, "groupId");
        requireIdentifier(sessionId, "sessionId");
        requireIdentifier(type, "type");
        Object lock = groupLocks.computeIfAbsent(groupId, ignored -> new Object());
        synchronized (lock) {
            ensureLoaded(groupId);
            long seq = sequences.computeIfAbsent(groupId,
                    ignored -> new AtomicLong()).incrementAndGet();
            StarweaveSessionEvent event = new StarweaveSessionEvent(
                    groupId, sessionId, turnId, generation, seq,
                    System.currentTimeMillis(), type, payload);
            persist(event);
            compactIfNeeded(event);
            addToRing(groupId, event);
            lock.notifyAll();
            return event;
        }
    }

    public List<StarweaveSessionEvent> snapshot(String groupId,
                                                String sessionId,
                                                long afterSeq) {
        return read(groupId, sessionId, afterSeq, null).getEvents();
    }

    /** Reads a bounded durable history for initial session rendering. */
    public ReadResult sessionSnapshot(String groupId, String sessionId) {
        requireIdentifier(groupId, "groupId");
        requireIdentifier(sessionId, "sessionId");
        if (root == null) return read(groupId, sessionId, 0L, null);
        Path journal = journalPath(groupId, sessionId);
        if (!Files.isRegularFile(journal)) return read(groupId, sessionId, 0L, null);
        Deque<StarweaveSessionEvent> bounded = new ArrayDeque<>();
        boolean truncated = false;
        try (BufferedReader reader = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    StarweaveSessionEvent event = StarweaveSessionEvent.fromJson(
                            JSON.parseObject(line));
                    if (!groupId.equals(event.getGroupId())
                            || !sessionId.equals(event.getSessionId())) continue;
                    bounded.addLast(event);
                    if (bounded.size() > MAX_SNAPSHOT_EVENTS) {
                        bounded.removeFirst();
                        truncated = true;
                    }
                } catch (RuntimeException malformed) {
                    log.warn("Ignoring malformed Starweave event record in {}", journal);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read Starweave session snapshot", e);
        }
        List<StarweaveSessionEvent> result = Collections.unmodifiableList(
                new ArrayList<>(bounded));
        long first = result.isEmpty() ? 1L : result.get(0).getEventSeq();
        long latest = result.isEmpty() ? 0L
                : result.get(result.size() - 1).getEventSeq();
        return new ReadResult(result, first, latest, false, truncated);
    }

    public ReadResult read(String groupId, String sessionId, long afterSeq,
                           Long generation) {
        requireIdentifier(groupId, "groupId");
        Object lock = groupLocks.computeIfAbsent(groupId, ignored -> new Object());
        synchronized (lock) {
            ensureLoaded(groupId);
            Deque<StarweaveSessionEvent> ring = events.get(groupId);
            long latest = sequences.getOrDefault(groupId, new AtomicLong()).get();
            if (ring == null || ring.isEmpty()) {
                return new ReadResult(Collections.emptyList(), latest + 1, latest, false, false);
            }
            long first = ring.getFirst().getEventSeq();
            boolean resync = afterSeq > 0 && afterSeq < first - 1
                    && hasEvictedMatchingEvent(groupId, sessionId, afterSeq,
                    first, generation);
            List<StarweaveSessionEvent> result = new ArrayList<>();
            for (StarweaveSessionEvent event : ring) {
                if (event.getEventSeq() <= afterSeq) continue;
                if (sessionId != null && !sessionId.equals(event.getSessionId())) continue;
                if (generation != null && generation.longValue() != event.getGeneration()) continue;
                result.add(event);
            }
            return new ReadResult(Collections.unmodifiableList(result), first, latest, resync, false);
        }
    }

    private boolean hasEvictedMatchingEvent(String groupId, String sessionId,
                                            long afterSeq, long firstRingSeq,
                                            Long generation) {
        if (sessionId == null || root == null) return true;
        ReadResult durable = sessionSnapshot(groupId, sessionId);
        for (StarweaveSessionEvent event : durable.getEvents()) {
            if (event.getEventSeq() <= afterSeq || event.getEventSeq() >= firstRingSeq) continue;
            if (generation != null && generation.longValue() != event.getGeneration()) continue;
            return true;
        }
        return false;
    }

    public ReadResult await(String groupId, String sessionId, long afterSeq,
                            Long generation, long timeoutMillis) {
        long boundedTimeout = Math.max(0L, Math.min(timeoutMillis, TimeUnit.SECONDS.toMillis(30)));
        Object lock = groupLocks.computeIfAbsent(groupId, ignored -> new Object());
        long deadline = System.currentTimeMillis() + boundedTimeout;
        synchronized (lock) {
            while (true) {
                ReadResult result = read(groupId, sessionId, afterSeq, generation);
                if (!result.getEvents().isEmpty() || result.isResyncRequired()
                        || boundedTimeout == 0L) return result;
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) return result;
                try {
                    lock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return result;
                }
            }
        }
    }

    public boolean hasDurableEvents(String groupId, String sessionId) {
        requireIdentifier(groupId, "groupId");
        requireIdentifier(sessionId, "sessionId");
        if (root == null) return !snapshot(groupId, sessionId, 0).isEmpty();
        return Files.isRegularFile(journalPath(groupId, sessionId));
    }

    private void ensureLoaded(String groupId) {
        if (loadedGroups.putIfAbsent(groupId, Boolean.TRUE) != null) return;
        AtomicLong sequence = sequences.computeIfAbsent(groupId, ignored -> new AtomicLong());
        if (root == null) return;
        Path groupDir = groupPath(groupId);
        if (!Files.isDirectory(groupDir)) return;
        List<StarweaveSessionEvent> loaded = new ArrayList<>();
        try (java.util.stream.Stream<Path> children = Files.list(groupDir)) {
            children.filter(Files::isDirectory)
                    .map(path -> path.resolve(JOURNAL_FILE))
                    .filter(Files::isRegularFile)
                    .forEach(path -> readJournal(path, groupId, loaded));
        } catch (IOException e) {
            loadedGroups.remove(groupId);
            throw new IllegalStateException("failed to load Starweave event journal", e);
        }
        loaded.sort(Comparator.comparingLong(StarweaveSessionEvent::getEventSeq));
        for (StarweaveSessionEvent event : loaded) {
            sequence.accumulateAndGet(event.getEventSeq(), Math::max);
            addToRing(groupId, event);
        }
    }

    private void readJournal(Path path, String expectedGroup,
                             List<StarweaveSessionEvent> target) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    StarweaveSessionEvent event = StarweaveSessionEvent.fromJson(
                            JSON.parseObject(line));
                    if (!expectedGroup.equals(event.getGroupId())) {
                        log.warn("Ignoring Starweave event with mismatched group in {}", path);
                        continue;
                    }
                    target.add(event);
                } catch (RuntimeException malformed) {
                    // An interrupted append may leave one malformed tail record. Earlier
                    // complete records remain usable and a later append stays monotonic.
                    log.warn("Ignoring malformed Starweave event record in {}", path);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read Starweave event journal", e);
        }
    }

    private void persist(StarweaveSessionEvent event) {
        if (root == null) return;
        Path journal = journalPath(event.getGroupId(), event.getSessionId());
        try {
            Files.createDirectories(journal.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(journal,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                writer.write(event.toJson().toJSONString());
                writer.newLine();
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist Starweave event", e);
        }
    }

    private void compactIfNeeded(StarweaveSessionEvent event) {
        if (root == null || event.getEventSeq() % 256L != 0L) return;
        Path journal = journalPath(event.getGroupId(), event.getSessionId());
        try {
            if (Files.size(journal) <= MAX_JOURNAL_BYTES) return;
            ReadResult snapshot = sessionSnapshot(event.getGroupId(), event.getSessionId());
            Path temp = journal.resolveSibling(JOURNAL_FILE + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temp,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                for (StarweaveSessionEvent retained : snapshot.getEvents()) {
                    writer.write(retained.toJson().toJSONString());
                    writer.newLine();
                }
            }
            try {
                Files.move(temp, journal, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, journal,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Failed to compact Starweave event journal {}", journal, e);
        }
    }

    private void addToRing(String groupId, StarweaveSessionEvent event) {
        Deque<StarweaveSessionEvent> ring = events.computeIfAbsent(
                groupId, ignored -> new ArrayDeque<>());
        ring.addLast(event);
        while (ring.size() > capacity) ring.removeFirst();
    }

    private Path groupPath(String groupId) {
        return root.resolve(hash(groupId));
    }

    private Path journalPath(String groupId, String sessionId) {
        return groupPath(groupId).resolve(hash(sessionId)).resolve(JOURNAL_FILE);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    public static final class ReadResult {
        private final List<StarweaveSessionEvent> events;
        private final long firstAvailableSeq;
        private final long latestSeq;
        private final boolean resyncRequired;
        private final boolean snapshotTruncated;

        ReadResult(List<StarweaveSessionEvent> events, long firstAvailableSeq,
                   long latestSeq, boolean resyncRequired) {
            this(events, firstAvailableSeq, latestSeq, resyncRequired, false);
        }

        ReadResult(List<StarweaveSessionEvent> events, long firstAvailableSeq,
                   long latestSeq, boolean resyncRequired, boolean snapshotTruncated) {
            this.events = events;
            this.firstAvailableSeq = firstAvailableSeq;
            this.latestSeq = latestSeq;
            this.resyncRequired = resyncRequired;
            this.snapshotTruncated = snapshotTruncated;
        }

        public List<StarweaveSessionEvent> getEvents() { return events; }
        public long getFirstAvailableSeq() { return firstAvailableSeq; }
        public long getLatestSeq() { return latestSeq; }
        public boolean isResyncRequired() { return resyncRequired; }
        public boolean isSnapshotTruncated() { return snapshotTruncated; }

        public JSONObject toJson() {
            JSONObject value = new JSONObject(true);
            value.put("events", events.stream().map(StarweaveSessionEvent::toJson)
                    .collect(java.util.stream.Collectors.toList()));
            value.put("firstAvailableSeq", firstAvailableSeq);
            value.put("latestSeq", latestSeq);
            value.put("resyncRequired", resyncRequired);
            value.put("snapshotTruncated", snapshotTruncated);
            return value;
        }
    }
}
