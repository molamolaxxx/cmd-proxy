package com.mola.cmd.proxy.app.acp.registry;

import com.alibaba.fastjson.JSON;
import com.mola.cmd.proxy.app.acp.registry.model.RegistryConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

public final class RegistryConfigStore {
    private final Path directory;
    public RegistryConfigStore(Path directory) { this.directory = directory; }
    public Path directory() { return directory; }
    public RegistryConfig load() throws IOException {
        Path file = directory.resolve("config.json");
        if (!Files.exists(file)) return new RegistryConfig();
        RegistryConfig config = JSON.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), RegistryConfig.class);
        if (config == null) throw new IOException("注册配置为空");
        config.validate();
        return config;
    }
    public void save(RegistryConfig config) throws IOException { write("config.json", JSON.toJSONString(config, true)); }
    public void write(String name, String content) throws IOException {
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, ".registry-", ".tmp");
        try {
            protect(temp);
            Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
            try { Files.move(temp, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    public static void protect(Path file) throws IOException {
        if (Files.getFileStore(file).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
}
