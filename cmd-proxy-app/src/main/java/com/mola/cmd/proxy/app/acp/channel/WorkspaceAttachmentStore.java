package com.mola.cmd.proxy.app.acp.channel;

import com.mola.cmd.proxy.app.acp.channel.model.ChannelAttachment;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Safely stages decrypted external attachments below the bound ACP workspace. */
final class WorkspaceAttachmentStore {
    private static final long MAX_ATTACHMENT_BYTES = 50L * 1024L * 1024L;
    private static final long MAX_MESSAGE_BYTES = 100L * 1024L * 1024L;
    private static final int MAX_ATTACHMENTS = 10;

    List<String> save(String workspace, String channelId, String eventId,
                      List<ChannelAttachment> attachments) throws IOException {
        if (attachments == null || attachments.isEmpty()) return Collections.emptyList();
        if (attachments.size() > MAX_ATTACHMENTS) {
            throw new IOException("too many attachments: " + attachments.size());
        }
        long total = 0L;
        for (ChannelAttachment attachment : attachments) {
            if (attachment.size() > MAX_ATTACHMENT_BYTES) {
                throw new IOException("attachment exceeds 50 MiB");
            }
            total += attachment.size();
            if (total > MAX_MESSAGE_BYTES) throw new IOException("message attachments exceed 100 MiB");
        }

        Path workspaceRoot = Paths.get(workspace).toAbsolutePath().normalize();
        Path targetDir = workspaceRoot.resolve(".cmd-proxy").resolve("inbox")
                .resolve("wecom").resolve(segment(channelId, "channel"))
                .resolve(segment(eventId, "message")).normalize();
        if (!targetDir.startsWith(workspaceRoot)) throw new IOException("invalid attachment directory");
        Files.createDirectories(targetDir);

        List<String> paths = new ArrayList<>();
        int currentIndex = 0;
        int quoteIndex = 0;
        for (ChannelAttachment attachment : attachments) {
            int index = attachment.getOrigin() == ChannelAttachment.Origin.QUOTE
                    ? ++quoteIndex : ++currentIndex;
            String prefix = attachment.getOrigin() == ChannelAttachment.Origin.QUOTE
                    ? "quote-" : "current-";
            String fallback = String.format(Locale.ROOT, "%02d", index)
                    + extensionFor(attachment);
            String safeName = fileName(attachment.getFileName(), fallback);
            Path destination = unique(targetDir, prefix + safeName).normalize();
            if (!destination.startsWith(targetDir)) throw new IOException("invalid attachment filename");
            Path temporary = Files.createTempFile(targetDir, ".download-", ".part");
            try {
                Files.write(temporary, attachment.getContent());
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, destination);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            paths.add(destination.toAbsolutePath().toString());
        }
        return Collections.unmodifiableList(paths);
    }

    private static Path unique(Path directory, String name) {
        Path candidate = directory.resolve(name);
        if (!Files.exists(candidate)) return candidate;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        int suffix = 2;
        do {
            candidate = directory.resolve(base + "-" + suffix++ + extension);
        } while (Files.exists(candidate));
        return candidate;
    }

    private static String fileName(String input, String fallback) {
        if (input == null || input.trim().isEmpty()) return fallback;
        String name = input.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        StringBuilder cleaned = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            cleaned.append(c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0 ? '_' : c);
        }
        name = cleaned.toString().trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return fallback;
        return name.length() <= 160 ? name : name.substring(name.length() - 160);
    }

    private static String segment(String value, String fallback) {
        String safe = value == null ? "" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.isEmpty() || ".".equals(safe) || "..".equals(safe)) return fallback;
        return safe.length() <= 120 ? safe : safe.substring(0, 120);
    }

    private static String extensionFor(ChannelAttachment attachment) {
        String mime = attachment.getMimeType();
        if (mime != null) {
            if (mime.startsWith("image/jpeg")) return ".jpg";
            if (mime.startsWith("image/png")) return ".png";
            if (mime.startsWith("image/gif")) return ".gif";
            if (mime.startsWith("image/webp")) return ".webp";
        }
        return attachment.getKind() == ChannelAttachment.Kind.IMAGE ? ".jpg" : ".bin";
    }
}
