package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistent desired/current state for environment-local Starweave sessions. */
public final class StarweaveSessionIndex {

    private static final int SCHEMA_VERSION = 1;
    private final Path path;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public StarweaveSessionIndex() {
        this(CmdProxyHome.resolve("starweave/sessions-index.json"));
    }

    StarweaveSessionIndex(Path path) {
        this.path = path.toAbsolutePath().normalize();
        load();
    }

    public synchronized Entry get(String groupId) {
        Entry entry = entries.get(requireText(groupId, "groupId"));
        return entry == null ? null : entry.copy();
    }

    public synchronized List<Entry> snapshot() {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries.values()) result.add(entry.copy());
        return Collections.unmodifiableList(result);
    }

    public synchronized Entry activate(String robotName, String groupId,
                                       String sessionId) {
        String key = requireText(groupId, "groupId");
        Entry current = entries.get(key);
        long generation = current == null ? 1L : current.generation + 1L;
        Entry updated = new Entry(requireText(robotName, "robotName"), key,
                trimToNull(sessionId), generation, true, false,
                System.currentTimeMillis());
        entries.put(key, updated);
        persist();
        return updated.copy();
    }

    public synchronized Entry updateSession(String groupId, String sessionId) {
        String key = requireText(groupId, "groupId");
        Entry current = entries.get(key);
        if (current == null) throw new IllegalStateException("session entry not found: " + key);
        Entry updated = new Entry(current.robotName, key,
                requireText(sessionId, "sessionId"), current.generation + 1L,
                true, false, System.currentTimeMillis());
        entries.put(key, updated);
        persist();
        return updated.copy();
    }

    public synchronized Entry markDeleted(String groupId) {
        String key = requireText(groupId, "groupId");
        Entry current = entries.get(key);
        if (current == null) return null;
        Entry deleted = new Entry(current.robotName, key, null,
                current.generation + 1L, false, true,
                System.currentTimeMillis());
        entries.put(key, deleted);
        persist();
        return deleted.copy();
    }

    private void load() {
        if (!Files.isRegularFile(path)) return;
        try {
            JSONObject root = JSON.parseObject(new String(
                    Files.readAllBytes(path), StandardCharsets.UTF_8));
            if (root.getIntValue("schemaVersion") != SCHEMA_VERSION) {
                throw new IllegalStateException("unsupported Starweave session index schema");
            }
            JSONArray array = root.getJSONArray("entries");
            if (array == null) return;
            for (int i = 0; i < array.size(); i++) {
                JSONObject item = array.getJSONObject(i);
                if (item == null) continue;
                Entry entry = Entry.from(item);
                entries.put(entry.groupId, entry);
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to load Starweave session index: " + path, e);
        }
    }

    private void persist() {
        try {
            Files.createDirectories(path.getParent());
            JSONObject root = new JSONObject(true);
            root.put("schemaVersion", SCHEMA_VERSION);
            JSONArray array = new JSONArray();
            for (Entry entry : entries.values()) array.add(entry.toJson());
            root.put("entries", array);
            byte[] bytes = JSON.toJSONString(root, SerializerFeature.PrettyFormat)
                    .getBytes(StandardCharsets.UTF_8);
            Path temp = Files.createTempFile(path.getParent(),
                    path.getFileName().toString(), ".tmp");
            try {
                Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist Starweave session index: " + path, e);
        }
    }

    private static String requireText(String value, String field) {
        String text = trimToNull(value);
        if (text == null) throw new IllegalArgumentException(field + " must not be blank");
        return text;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    public static final class Entry {
        private final String robotName;
        private final String groupId;
        private final String currentSessionId;
        private final long generation;
        private final boolean active;
        private final boolean forceNewOnNextOpen;
        private final long updatedAt;

        private Entry(String robotName, String groupId, String currentSessionId,
                      long generation, boolean active,
                      boolean forceNewOnNextOpen, long updatedAt) {
            this.robotName = requireText(robotName, "robotName");
            this.groupId = requireText(groupId, "groupId");
            this.currentSessionId = trimToNull(currentSessionId);
            if (generation < 1L) throw new IllegalArgumentException("generation must be positive");
            this.generation = generation;
            this.active = active;
            this.forceNewOnNextOpen = forceNewOnNextOpen;
            this.updatedAt = updatedAt;
        }

        static Entry from(JSONObject value) {
            return new Entry(value.getString("robotName"), value.getString("groupId"),
                    value.getString("currentSessionId"), value.getLongValue("generation"),
                    value.getBooleanValue("active"),
                    value.getBooleanValue("forceNewOnNextOpen"),
                    value.getLongValue("updatedAt"));
        }

        JSONObject toJson() {
            JSONObject value = new JSONObject(true);
            value.put("robotName", robotName);
            value.put("groupId", groupId);
            value.put("currentSessionId", currentSessionId);
            value.put("generation", generation);
            value.put("active", active);
            value.put("forceNewOnNextOpen", forceNewOnNextOpen);
            value.put("updatedAt", updatedAt);
            return value;
        }

        Entry copy() {
            return new Entry(robotName, groupId, currentSessionId, generation,
                    active, forceNewOnNextOpen, updatedAt);
        }

        public String getRobotName() { return robotName; }
        public String getGroupId() { return groupId; }
        public String getCurrentSessionId() { return currentSessionId; }
        public long getGeneration() { return generation; }
        public boolean isActive() { return active; }
        public boolean isForceNewOnNextOpen() { return forceNewOnNextOpen; }
        public long getUpdatedAt() { return updatedAt; }
    }
}
