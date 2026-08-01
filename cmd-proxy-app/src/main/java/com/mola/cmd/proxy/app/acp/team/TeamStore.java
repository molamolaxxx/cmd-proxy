package com.mola.cmd.proxy.app.acp.team;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamOperationRecord;
import com.mola.cmd.proxy.app.acp.team.model.TeamTombstone;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Team 权威元数据的文件存储。
 *
 * <p>只负责安全路径、原子写入与版本 CAS；业务幂等和状态机由 TeamManager 负责。</p>
 */
public final class TeamStore {

    private static final String SAFE_SEGMENT = "[a-zA-Z0-9._-]+";

    private final Path teamsRoot;
    private final Path operationsRoot;
    private final Path tombstonesRoot;
    private final Gson gson;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public TeamStore() {
        this(CmdProxyHome.resolve("teams"));
    }

    public TeamStore(Path teamsRoot) {
        this.teamsRoot = teamsRoot.toAbsolutePath().normalize();
        this.operationsRoot = this.teamsRoot.resolve("operations");
        this.tombstonesRoot = this.teamsRoot.resolve("tombstones");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    /**
     * 新建要求 version=1 且文件不存在；更新要求 version 恰好比持久版本大 1。
     */
    public void saveTeam(TeamDefinition definition) throws IOException {
        String teamId = requireSafeSegment(definition.getTeamId(), "teamId");
        synchronized (lock("team:" + teamId)) {
            Optional<TeamDefinition> existing = loadTeamUnlocked(teamId);
            if (!existing.isPresent()) {
                if (definition.getVersion() != 1L) {
                    throw new VersionConflictException(teamId, 0L, definition.getVersion());
                }
            } else {
                long expected = existing.get().getVersion() + 1L;
                if (definition.getVersion() != expected) {
                    throw new VersionConflictException(
                            teamId, existing.get().getVersion(), definition.getVersion());
                }
            }
            atomicWrite(teamFile(teamId), gson.toJson(definition));
        }
    }

    public Optional<TeamDefinition> loadTeam(String teamId) throws IOException {
        String safeId = requireSafeSegment(teamId, "teamId");
        synchronized (lock("team:" + safeId)) {
            return loadTeamUnlocked(safeId);
        }
    }

    public List<TeamDefinition> loadTeams() throws IOException {
        if (!Files.isDirectory(teamsRoot)) {
            return new ArrayList<>();
        }
        List<TeamDefinition> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(teamsRoot)) {
            for (Path child : stream) {
                Path file = child.resolve("team.json");
                if (Files.isDirectory(child) && Files.isRegularFile(file)) {
                    result.add(readJson(file, TeamDefinition.class));
                }
            }
        }
        result.sort(Comparator.comparing(TeamDefinition::getTeamId));
        return result;
    }

    public void saveOperation(TeamOperationRecord operation) throws IOException {
        String requestId = requireSafeSegment(operation.getRequestId(), "requestId");
        synchronized (lock("operation:" + requestId)) {
            atomicWrite(operationsRoot.resolve(requestId + ".json"), gson.toJson(operation));
        }
    }

    public Optional<TeamOperationRecord> loadOperation(String requestId) throws IOException {
        String safeId = requireSafeSegment(requestId, "requestId");
        Path file = operationsRoot.resolve(safeId + ".json");
        synchronized (lock("operation:" + safeId)) {
            return Files.isRegularFile(file)
                    ? Optional.of(readJson(file, TeamOperationRecord.class))
                    : Optional.empty();
        }
    }

    public List<TeamOperationRecord> loadOperations() throws IOException {
        return loadFlatJsonFiles(
                operationsRoot, TeamOperationRecord.class,
                Comparator.comparing(TeamOperationRecord::getCreatedAt));
    }

    public void saveTombstone(TeamTombstone tombstone) throws IOException {
        String teamId = requireSafeSegment(tombstone.getTeamId(), "teamId");
        synchronized (lock("tombstone:" + teamId)) {
            atomicWrite(tombstonesRoot.resolve(teamId + ".json"), gson.toJson(tombstone));
        }
    }

    public Optional<TeamTombstone> loadTombstone(String teamId) throws IOException {
        String safeId = requireSafeSegment(teamId, "teamId");
        Path file = tombstonesRoot.resolve(safeId + ".json");
        synchronized (lock("tombstone:" + safeId)) {
            return Files.isRegularFile(file)
                    ? Optional.of(readJson(file, TeamTombstone.class))
                    : Optional.empty();
        }
    }

    public List<TeamTombstone> loadTombstones() throws IOException {
        return loadFlatJsonFiles(
                tombstonesRoot, TeamTombstone.class,
                Comparator.comparing(TeamTombstone::getDeletedAt));
    }

    public void deleteTeam(String teamId) throws IOException {
        String safeId = requireSafeSegment(teamId, "teamId");
        synchronized (lock("team:" + safeId)) {
            Path dir = teamsRoot.resolve(safeId);
            Files.deleteIfExists(dir.resolve("team.json"));
            Files.deleteIfExists(dir);
        }
    }

    public void deleteOperation(String requestId) throws IOException {
        String safeId = requireSafeSegment(requestId, "requestId");
        synchronized (lock("operation:" + safeId)) {
            Files.deleteIfExists(operationsRoot.resolve(safeId + ".json"));
        }
    }

    public void deleteTombstone(String teamId) throws IOException {
        String safeId = requireSafeSegment(teamId, "teamId");
        synchronized (lock("tombstone:" + safeId)) {
            Files.deleteIfExists(tombstonesRoot.resolve(safeId + ".json"));
        }
    }

    public Path getTeamsRoot() {
        return teamsRoot;
    }

    private Optional<TeamDefinition> loadTeamUnlocked(String safeTeamId) throws IOException {
        Path file = teamFile(safeTeamId);
        return Files.isRegularFile(file)
                ? Optional.of(readJson(file, TeamDefinition.class))
                : Optional.empty();
    }

    private Path teamFile(String safeTeamId) {
        return teamsRoot.resolve(safeTeamId).resolve("team.json");
    }

    private <T> T readJson(Path file, Class<T> type) throws IOException {
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            T value = gson.fromJson(content, type);
            if (value == null) {
                throw new IOException("empty JSON object: " + file);
            }
            return value;
        } catch (RuntimeException e) {
            throw new IOException("invalid Team store JSON: " + file, e);
        }
    }

    private <T> List<T> loadFlatJsonFiles(
            Path root, Class<T> type, Comparator<T> comparator) throws IOException {
        List<T> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(root, "*.json")) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    result.add(readJson(file, type));
                }
            }
        }
        result.sort(comparator);
        return result;
    }

    private void atomicWrite(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Object lock(String key) {
        return locks.computeIfAbsent(key, ignored -> new Object());
    }

    private static String requireSafeSegment(String value, String field) {
        if (value == null || !value.matches(SAFE_SEGMENT)
                || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException(field + " is not a safe path segment");
        }
        return value;
    }

    public static final class VersionConflictException extends IOException {
        private final String teamId;
        private final long persistedVersion;
        private final long attemptedVersion;

        private VersionConflictException(String teamId, long persistedVersion,
                                         long attemptedVersion) {
            super("team version conflict: teamId=" + teamId
                    + ", persistedVersion=" + persistedVersion
                    + ", attemptedVersion=" + attemptedVersion);
            this.teamId = teamId;
            this.persistedVersion = persistedVersion;
            this.attemptedVersion = attemptedVersion;
        }

        public String getTeamId() {
            return teamId;
        }

        public long getPersistedVersion() {
            return persistedVersion;
        }

        public long getAttemptedVersion() {
            return attemptedVersion;
        }
    }
}
