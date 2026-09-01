package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.UUID;

/** Server-owned, expiring upload staging. No caller-provided path is resolved. */
final class StarweaveUploadStore {
    static final int MAX_FILE_BYTES = 20 * 1024 * 1024;
    private static final long TTL_MILLIS = 60L * 60L * 1000L;
    private final Path root;

    StarweaveUploadStore() {
        this(CmdProxyHome.resolve("starweave/uploads"));
    }

    StarweaveUploadStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    JSONObject stage(String groupId, String sessionId, long generation,
                     String originalName, String base64) {
        String fileName = safeFileName(originalName);
        if (base64 == null) throw new IllegalArgumentException("contentBase64 is required");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("contentBase64 is invalid");
        }
        if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("file size must be between 1 byte and 20 MiB");
        }
        cleanupExpired();
        String uploadId = UUID.randomUUID().toString();
        Path directory = root.resolve(uploadId);
        JSONObject metadata = new JSONObject(true);
        metadata.put("schemaVersion", 1);
        metadata.put("uploadId", uploadId);
        metadata.put("groupId", groupId);
        metadata.put("sessionId", sessionId);
        metadata.put("generation", generation);
        metadata.put("fileName", fileName);
        metadata.put("size", bytes.length);
        metadata.put("createdAt", System.currentTimeMillis());
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve("content.bin"), bytes);
            atomicWrite(directory.resolve("metadata.json"), metadata.toJSONString());
        } catch (IOException e) {
            throw new IllegalStateException("failed to stage Starweave upload", e);
        }
        return metadata;
    }

    ResolvedUpload resolve(String uploadId, String groupId, String sessionId,
                           long generation) {
        if (uploadId == null || !uploadId.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("invalid uploadId");
        }
        Path directory = root.resolve(uploadId).normalize();
        if (!directory.getParent().equals(root)) throw new IllegalArgumentException("invalid uploadId");
        try {
            JSONObject metadata = JSON.parseObject(new String(Files.readAllBytes(
                    directory.resolve("metadata.json")), StandardCharsets.UTF_8));
            if (metadata == null
                    || !groupId.equals(metadata.getString("groupId"))
                    || !sessionId.equals(metadata.getString("sessionId"))
                    || generation != metadata.getLongValue("generation")) {
                throw new IllegalArgumentException("upload does not belong to current session generation");
            }
            if (System.currentTimeMillis() - metadata.getLongValue("createdAt") > TTL_MILLIS) {
                delete(uploadId);
                throw new IllegalArgumentException("upload has expired");
            }
            Path content = directory.resolve("content.bin");
            long actualSize = Files.size(content);
            if (actualSize < 1L || actualSize > MAX_FILE_BYTES
                    || actualSize != metadata.getLongValue("size")) {
                throw new IllegalStateException("staged upload is incomplete");
            }
            byte[] bytes = Files.readAllBytes(content);
            if (bytes.length != metadata.getLongValue("size")
                    || bytes.length > MAX_FILE_BYTES) {
                throw new IllegalStateException("staged upload is incomplete");
            }
            return new ResolvedUpload(uploadId, metadata.getString("fileName"), bytes);
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
                throw (RuntimeException) e;
            }
            throw new IllegalArgumentException("upload not found: " + uploadId);
        }
    }

    void delete(String uploadId) {
        if (uploadId == null || !uploadId.matches("[0-9a-fA-F-]{36}")) return;
        Path directory = root.resolve(uploadId).normalize();
        if (!directory.getParent().equals(root)) return;
        try {
            Files.deleteIfExists(directory.resolve("content.bin"));
            Files.deleteIfExists(directory.resolve("metadata.json"));
            Files.deleteIfExists(directory);
        } catch (IOException ignored) { }
    }

    private void cleanupExpired() {
        if (!Files.isDirectory(root)) return;
        long cutoff = System.currentTimeMillis() - TTL_MILLIS;
        try (java.util.stream.Stream<Path> directories = Files.list(root)) {
            directories.filter(Files::isDirectory).forEach(directory -> {
                try {
                    if (Files.getLastModifiedTime(directory).toMillis() < cutoff) {
                        delete(directory.getFileName().toString());
                    }
                } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static String safeFileName(String value) {
        if (value == null) throw new IllegalArgumentException("fileName is required");
        String normalized = value.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        normalized = normalized.replaceAll("[\\p{Cntrl}]", "_");
        if (normalized.isEmpty() || ".".equals(normalized) || "..".equals(normalized)) {
            throw new IllegalArgumentException("fileName is invalid");
        }
        if (normalized.length() > 180) normalized = normalized.substring(0, 180);
        return normalized;
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static final class ResolvedUpload {
        final String uploadId;
        final String fileName;
        final byte[] bytes;

        ResolvedUpload(String uploadId, String fileName, byte[] bytes) {
            this.uploadId = uploadId;
            this.fileName = fileName;
            this.bytes = bytes;
        }
    }
}
