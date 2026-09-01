package com.mola.cmd.proxy.app.acp.filepreview;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;

public final class TextFilePreviewReader {
    public static final int HARD_MAX_BYTES = 1024 * 1024;
    public static final int MAX_LINES = 10_000;
    private static final Semaphore CONCURRENCY = new Semaphore(6);

    private TextFilePreviewReader() { }

    public static TextFilePreviewResult read(String requestId, String workspacePath,
                                             String requestedPath, Integer requestedMaxBytes,
                                             String requestedCharset) {
        if (isBlank(workspacePath) || isBlank(requestedPath) || requestedPath.length() > 8192) {
            return error(requestId, "INVALID_ARGUMENT", "workspace/path is required", false);
        }
        if (!CONCURRENCY.tryAcquire()) {
            return error(requestId, "BUSY", "too many concurrent file reads", true);
        }
        try {
            return readInner(requestId, workspacePath, requestedPath,
                    requestedMaxBytes, requestedCharset);
        } finally {
            CONCURRENCY.release();
        }
    }

    private static TextFilePreviewResult readInner(String requestId, String workspacePath,
                                                    String requestedPath, Integer requestedMaxBytes,
                                                    String requestedCharset) {
        int maxBytes = requestedMaxBytes == null
                ? HARD_MAX_BYTES : Math.min(HARD_MAX_BYTES, requestedMaxBytes);
        if (maxBytes < 1) return error(requestId, "INVALID_ARGUMENT", "maxBytes must be positive", false);
        try {
            Path workspace = Paths.get(workspacePath).toRealPath();
            String normalizedRequest = normalizeRequestedPath(requestedPath.trim(), isWindows());
            if (isUnsafeWindowsPath(normalizedRequest)) {
                return error(requestId, "PATH_NOT_ALLOWED", "unsafe Windows path", false);
            }
            Path candidate = Paths.get(normalizedRequest);
            if (!candidate.isAbsolute()) candidate = workspace.resolve(candidate).normalize();
            if (Files.isSymbolicLink(candidate)) {
                return error(requestId, "PATH_NOT_ALLOWED", "symbolic links are not allowed", false);
            }
            Path realPath;
            try {
                realPath = candidate.toRealPath();
            } catch (java.nio.file.NoSuchFileException e) {
                return error(requestId, "FILE_NOT_FOUND", "file not found", false);
            }
            if (isSpecialUnixPath(realPath)) {
                return error(requestId, "PATH_NOT_ALLOWED", "special system path is not allowed", false);
            }
            BasicFileAttributes attrs = Files.readAttributes(
                    realPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile()) return error(requestId, "NOT_REGULAR_FILE", "path is not a regular file", false);
            if (!Files.isReadable(realPath)) return error(requestId, "PERMISSION_DENIED", "file is not readable", false);
            if (attrs.size() > maxBytes) return error(requestId, "FILE_TOO_LARGE", "file exceeds preview limit", false);
            byte[] bytes = readLimited(realPath, maxBytes);
            Decoded decoded = decode(bytes, requestedCharset);
            int lines = countLines(decoded.content);
            if (lines > MAX_LINES) return error(requestId, "FILE_TOO_LARGE", "file has too many lines", false);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("fileName", realPath.getFileName().toString());
            data.put("content", decoded.content);
            data.put("charset", decoded.charset);
            data.put("mediaType", mediaType(realPath));
            data.put("bytesRead", bytes.length);
            data.put("fileSize", attrs.size());
            data.put("lineCount", lines);
            data.put("truncated", false);
            data.put("lastModified", attrs.lastModifiedTime().toMillis());
            return TextFilePreviewResult.success(requestId, data);
        } catch (BinaryFileException e) {
            return error(requestId, "BINARY_FILE", "binary file is not supported", false);
        } catch (CharacterCodingException e) {
            return error(requestId, "INVALID_ENCODING", "file encoding is not supported", false);
        } catch (SecurityException e) {
            return error(requestId, "PERMISSION_DENIED", "file access denied", false);
        } catch (IOException e) {
            return error(requestId, "IO_ERROR", safeMessage(e), true);
        } catch (RuntimeException e) {
            return error(requestId, "INVALID_ARGUMENT", safeMessage(e), false);
        }
    }

    private static byte[] readLimited(Path path, int limit) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > limit) throw new IOException("file exceeds preview limit");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static Decoded decode(byte[] bytes, String requestedCharset) throws CharacterCodingException {
        Charset charset;
        int offset = 0;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) {
            charset = StandardCharsets.UTF_8; offset = 3;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
            charset = StandardCharsets.UTF_16LE; offset = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
            charset = StandardCharsets.UTF_16BE; offset = 2;
        } else {
            charset = requestedCharset(requestedCharset);
        }
        if (looksBinary(bytes, offset, charset)) throw new BinaryFileException();
        CharBuffer content = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
        return new Decoded(content.toString(), charset.name());
    }

    private static Charset requestedCharset(String value) {
        if (isBlank(value) || "UTF-8".equalsIgnoreCase(value)) return StandardCharsets.UTF_8;
        if ("GB18030".equalsIgnoreCase(value)) return Charset.forName("GB18030");
        throw new IllegalArgumentException("charset must be UTF-8 or GB18030");
    }

    private static boolean looksBinary(byte[] bytes, int offset, Charset charset) {
        if (StandardCharsets.UTF_16LE.equals(charset) || StandardCharsets.UTF_16BE.equals(charset)) return false;
        int controls = 0;
        int sampleEnd = Math.min(bytes.length, offset + 8192);
        for (int index = offset; index < sampleEnd; index++) {
            int value = bytes[index] & 0xff;
            if (value == 0) return true;
            if (value < 0x20 && value != '\n' && value != '\r' && value != '\t'
                    && value != '\f' && value != '\b') controls++;
        }
        return sampleEnd > offset && controls * 20 > sampleEnd - offset;
    }

    private static int countLines(String content) {
        if (content.isEmpty()) return 0;
        int lines = 1;
        for (int i = 0; i < content.length(); i++) if (content.charAt(i) == '\n') lines++;
        return lines;
    }

    private static String mediaType(Path path) {
        try {
            String probed = Files.probeContentType(path);
            if (probed != null && !probed.trim().isEmpty()) return probed;
        } catch (IOException ignored) { }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".md") || name.endsWith(".markdown")) return "text/markdown";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".xml")) return "application/xml";
        return "text/plain";
    }

    static String normalizeRequestedPath(String value, boolean windows) {
        if (value.regionMatches(true, 0, "file:///", 0, 8)) {
            String result = value.substring(7);
            if (windows && result.matches("^/[A-Za-z]:[\\\\/].*")) result = result.substring(1);
            return result;
        }
        if (value.regionMatches(true, 0, "file://", 0, 7)) value = value.substring(7);
        if (windows && value.matches("^/[A-Za-z]:[\\\\/].*")) return value.substring(1);
        return value;
    }

    private static boolean isUnsafeWindowsPath(String value) {
        if (!isWindows()) return false;
        String normalized = value.replace('/', '\\');
        if (normalized.startsWith("\\\\") || normalized.startsWith("\\\\?\\")
                || normalized.startsWith("\\\\.\\")) return true;
        int firstColon = normalized.indexOf(':');
        return firstColon >= 0 && firstColon != 1;
    }

    private static boolean isSpecialUnixPath(Path path) {
        if (isWindows()) return false;
        String value = path.toString();
        return value.equals("/proc") || value.startsWith("/proc/")
                || value.equals("/sys") || value.startsWith("/sys/")
                || value.equals("/dev") || value.startsWith("/dev/");
    }

    private static boolean isWindows() { return java.io.File.separatorChar == '\\'; }
    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
    private static TextFilePreviewResult error(String requestId, String code, String message, boolean retryable) {
        return TextFilePreviewResult.error(requestId, code, message, retryable);
    }

    private static final class Decoded {
        private final String content;
        private final String charset;
        private Decoded(String content, String charset) { this.content = content; this.charset = charset; }
    }
    private static final class BinaryFileException extends CharacterCodingException {
        private static final long serialVersionUID = 1L;
    }
}
