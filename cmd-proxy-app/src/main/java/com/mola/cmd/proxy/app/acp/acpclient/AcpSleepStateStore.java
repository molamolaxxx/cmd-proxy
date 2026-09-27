package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Set;

/** Persists MAIN logical clients that should remain asleep across process restarts. */
public final class AcpSleepStateStore {

    private static final Logger logger = LoggerFactory.getLogger(AcpSleepStateStore.class);
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private final Set<String> sleepingLogicalIds = new LinkedHashSet<>();

    public AcpSleepStateStore() {
        this(CmdProxyHome.resolve("acp-sleep-state.json"));
    }

    AcpSleepStateStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
        load();
    }

    public synchronized boolean isSleeping(String logicalId) {
        return sleepingLogicalIds.contains(requireLogicalId(logicalId));
    }

    public synchronized void markSleeping(String logicalId) {
        String key = requireLogicalId(logicalId);
        if (sleepingLogicalIds.add(key)) persist();
    }

    public synchronized void markAwake(String logicalId) {
        String key = requireLogicalId(logicalId);
        if (sleepingLogicalIds.remove(key)) persist();
    }

    private void load() {
        if (!Files.isRegularFile(path)) return;
        try {
            JsonObject root = JsonParser.parseString(new String(
                    Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("schemaVersion")
                    || root.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
                throw new IOException("unsupported schemaVersion");
            }
            JsonArray values = root.getAsJsonArray("sleepingLogicalIds");
            if (values == null) return;
            values.forEach(value -> {
                String logicalId = value.getAsString();
                if (logicalId != null && !logicalId.trim().isEmpty()) {
                    sleepingLogicalIds.add(logicalId.trim());
                }
            });
        } catch (Exception error) {
            sleepingLogicalIds.clear();
            logger.warn("读取 ACP 睡眠状态失败，将按 READY 恢复: path={}", path, error);
        }
    }

    private void persist() {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            JsonObject root = new JsonObject();
            root.addProperty("schemaVersion", SCHEMA_VERSION);
            JsonArray values = new JsonArray();
            for (String logicalId : sleepingLogicalIds) values.add(logicalId);
            root.add("sleepingLogicalIds", values);
            byte[] bytes = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
            Path temp = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
            try {
                Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException error) {
            logger.warn("持久化 ACP 睡眠状态失败: path={}", path, error);
        }
    }

    private static String requireLogicalId(String logicalId) {
        if (logicalId == null || logicalId.trim().isEmpty()) {
            throw new IllegalArgumentException("logicalId must not be blank");
        }
        return logicalId.trim();
    }
}
