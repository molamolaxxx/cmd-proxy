package com.mola.cmd.proxy.app.acp.team;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Team 会话历史归档。Memory 不在此范围内，删除 Team 不删除共享 memory。
 */
public final class TeamHistoryArchiver {

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().create();
    private final Path sessionTeamRoot;
    private final Path archiveRoot;

    public TeamHistoryArchiver() {
        this(CmdProxyHome.resolve("session").resolve("team"),
                CmdProxyHome.resolve("team-archive"));
    }

    public TeamHistoryArchiver(Path sessionTeamRoot, Path archiveRoot) {
        this.sessionTeamRoot = sessionTeamRoot.toAbsolutePath().normalize();
        this.archiveRoot = archiveRoot.toAbsolutePath().normalize();
    }

    public long archive(String teamId, long expireAt) throws IOException {
        String safeId = requireSafeId(teamId);
        Path source = sessionTeamRoot.resolve(safeId).normalize();
        Path target = archiveRoot.resolve(safeId).normalize();
        if (!source.startsWith(sessionTeamRoot)
                || !target.startsWith(archiveRoot)) {
            throw new IOException("Team archive path escaped root");
        }
        Files.createDirectories(archiveRoot);
        if (Files.exists(source) && !Files.exists(target)) {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, target);
            }
        }
        if (Files.exists(target)) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("teamId", safeId);
            metadata.put("archivedAt", System.currentTimeMillis());
            metadata.put("expireAt", expireAt);
            Files.write(target.resolve("archive.json"),
                    GSON.toJson(metadata).getBytes(StandardCharsets.UTF_8));
        }
        return expireAt;
    }

    public boolean deleteExpired(String teamId, long now) throws IOException {
        String safeId = requireSafeId(teamId);
        Path target = archiveRoot.resolve(safeId).normalize();
        Path metadata = target.resolve("archive.json");
        if (!Files.isRegularFile(metadata)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> value = GSON.fromJson(
                new String(Files.readAllBytes(metadata), StandardCharsets.UTF_8),
                Map.class);
        Object expireAt = value.get("expireAt");
        if (!(expireAt instanceof Number)
                || ((Number) expireAt).longValue() > now) {
            return false;
        }
        deleteTree(target);
        return true;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths
                    .sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String requireSafeId(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9._-]+")
                || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("teamId is not path-safe");
        }
        return value;
    }
}
