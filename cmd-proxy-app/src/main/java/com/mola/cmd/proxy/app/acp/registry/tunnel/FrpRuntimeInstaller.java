package com.mola.cmd.proxy.app.acp.registry.tunnel;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** 官方固定版本与固定 SHA256；只提取 frpc/frps，不执行解压命令。 */
public final class FrpRuntimeInstaller {
    public static final String VERSION = "0.71.0";
    private static final Map<String, String> HASHES = new HashMap<>();
    static {
        HASHES.put("linux_amd64", "84f27e39f11169f7adcef8e8b70c9329de17747b1f14dad9fb95eef5682ea716");
        HASHES.put("linux_arm64", "f33c293c275d8fc68c654b6fba8f10b2551d6463d09a9fc9cffb7227eae82266");
        HASHES.put("darwin_amd64", "1b1b4e2f1836e21e8733f1dddaacd4ed9ae67d7dbee39046b9d7b7eda6253637");
        HASHES.put("darwin_arm64", "45be02b186860d375ed49a8941ae9569628a54bf14e67fc36b29c98c99dabcc6");
        HASHES.put("windows_amd64", "9e5062e3e5cf07e67144a3a4acf175ef6a2486f3605dd6cf288bae34ab39819f");
    }
    private final Path root;
    public FrpRuntimeInstaller(Path root) { this.root = root; }
    public synchronized Path executable(String name) throws IOException {
        String platform = platform();
        String suffix = platform.startsWith("windows") ? ".exe" : "";
        Path directory = root.resolve(VERSION).resolve(platform);
        Path binary = directory.resolve(name + suffix);
        if (!Files.isRegularFile(directory.resolve(".verified")) || !Files.isRegularFile(binary)) install(directory, platform, suffix);
        return binary.toAbsolutePath();
    }
    static String platform() throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String platform = (os.contains("win") && !os.contains("darwin") ? "windows" : os.contains("mac") || os.contains("darwin") ? "darwin" : os.contains("linux") ? "linux" : "unsupported")
                + "_" + (arch.equals("amd64") || arch.equals("x86_64") ? "amd64" : arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : "unsupported");
        if (!HASHES.containsKey(platform)) throw new IOException("暂不支持此平台的 frp 自动安装: " + platform);
        return platform;
    }
    private void install(Path directory, String platform, String suffix) throws IOException {
        Files.createDirectories(directory);
        String archiveName = "frp_" + VERSION + "_" + platform + (suffix.isEmpty() ? ".tar.gz" : ".zip");
        Path archive = Files.createTempFile(directory, ".download-", ".tmp");
        try {
            download("https://github.com/fatedier/frp/releases/download/v" + VERSION + "/" + archiveName, archive);
            if (!HASHES.get(platform).equals(hash(archive))) throw new IOException("frp 下载校验失败");
            String prefix = "frp_" + VERSION + "_" + platform + "/";
            Set<String> remaining = new HashSet<>(Arrays.asList("frpc" + suffix, "frps" + suffix));
            if (suffix.isEmpty()) {
                try (InputStream input = new GZIPInputStream(Files.newInputStream(archive))) {
                    byte[] header = new byte[512];
                    while (readHeader(input, header)) {
                        String entry = new String(header, 0, 100, StandardCharsets.US_ASCII).split("\u0000", 2)[0];
                        String sizeText = new String(header, 124, 12, StandardCharsets.US_ASCII).replace("\u0000", "").trim();
                        long size;
                        try { size = Long.parseLong(sizeText.isEmpty() ? "0" : sizeText, 8); }
                        catch (NumberFormatException e) { throw new IOException("无效的 frp 压缩包", e); }
                        if (size < 0 || size > 128L * 1024 * 1024) throw new IOException("frp 压缩包条目过大");
                        String file = entry.startsWith(prefix) ? entry.substring(prefix.length()) : "";
                        if ((header[156] == 0 || header[156] == '0') && remaining.remove(file)) extract(input, directory.resolve(file), size);
                        else skipFully(input, size);
                        skipFully(input, (512 - size % 512) % 512);
                    }
                }
            } else {
                try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
                    ZipEntry entry;
                    while ((entry = input.getNextEntry()) != null) {
                        String file = entry.getName().startsWith(prefix) ? entry.getName().substring(prefix.length()) : "";
                        if (!entry.isDirectory() && remaining.remove(file)) extract(input, directory.resolve(file), -1);
                    }
                }
            }
            if (!remaining.isEmpty()) throw new IOException("frp 压缩包缺少程序");
            Files.write(directory.resolve(".verified"), HASHES.get(platform).getBytes(StandardCharsets.US_ASCII));
        } finally { Files.deleteIfExists(archive); }
    }
    private static boolean readHeader(InputStream input, byte[] header) throws IOException {
        int offset = 0, n;
        while (offset < 512 && (n = input.read(header, offset, 512 - offset)) >= 0) offset += n;
        if (offset == 0) return false;
        if (offset != 512) throw new EOFException("frp tar 截断");
        for (byte b : header) if (b != 0) return true;
        return false;
    }
    private static void skipFully(InputStream input, long size) throws IOException {
        while (size > 0) { long n = input.skip(size); if (n == 0) { if (input.read() < 0) throw new EOFException(); n = 1; } size -= n; }
    }
    private static void extract(InputStream input, Path target, long size) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".extract-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[8192]; long count = 0; int n;
                while ((size < 0 || count < size) && (n = input.read(buffer, 0, size < 0 ? buffer.length : (int)Math.min(buffer.length, size - count))) >= 0) {
                    count += n; if (count > 128L * 1024 * 1024) throw new IOException("frp 程序过大"); output.write(buffer, 0, n);
                }
                if (size >= 0 && count != size) throw new EOFException();
            }
            if (!temp.toFile().setExecutable(true, true)) throw new IOException("无法设置 frp 执行权限");
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private static void download(String url, Path target) throws IOException {
        URL current = new URL(url);
        for (int i = 0; i < 8; i++) {
            if (!"https".equals(current.getProtocol())) throw new IOException("frp 下载必须使用 HTTPS");
            HttpURLConnection connection = downloadConnection(current);
            connection.setConnectTimeout(15_000); connection.setReadTimeout(30_000); connection.setInstanceFollowRedirects(false);
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 307 || status == 308) { current = new URL(current, connection.getHeaderField("Location")); continue; }
                if (status != 200) throw new IOException("frp 下载失败: HTTP " + status);
                try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[8192]; long count = 0; int n;
                    while ((n = input.read(buffer)) >= 0) { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException(); count += n; if (count > 64L * 1024 * 1024) throw new IOException("frp 下载过大"); output.write(buffer, 0, n); }
                }
                return;
            } finally { connection.disconnect(); }
        }
        throw new IOException("frp 下载重定向过多");
    }
    private static HttpURLConnection downloadConnection(URL url) throws IOException {
        // Java 不自动读取 HTTPS_PROXY；仅安装下载使用环境代理，业务与回环转发不走它。
        String setting = System.getenv("HTTPS_PROXY");
        if (setting == null || setting.isEmpty()) setting = System.getenv("https_proxy");
        if (System.getProperty("https.proxyHost") != null || setting == null || setting.isEmpty())
            return (HttpURLConnection) url.openConnection();
        try {
            URI proxy = URI.create(setting.contains("://") ? setting : "http://" + setting);
            if (proxy.getHost() == null || proxy.getRawUserInfo() != null || !"http".equals(proxy.getScheme()))
                throw new IOException("frp 下载代理需为不含账号密码的 HTTP 代理");
            return (HttpURLConnection) url.openConnection(new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(proxy.getHost(), proxy.getPort() > 0 ? proxy.getPort() : 80)));
        } catch (IllegalArgumentException e) { throw new IOException("frp 下载代理地址无效", e); }
    }

    private static String hash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) { byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) >= 0) digest.update(buffer, 0, n); }
            StringBuilder result = new StringBuilder(); for (byte b : digest.digest()) result.append(String.format("%02x", b & 255)); return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }
}
