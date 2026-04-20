package com.mola.cmd.proxy.app.acp.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

/**
 * 路径工具类，提供统一的目录命名和数据迁移能力。
 * <p>
 * 新命名规则：将路径中的 / 等特殊字符替换为 -，去掉首尾 -。
 * 例如 /home/mola/IdeaProjects/cmd-proxy → home-mola-IdeaProjects-cmd-proxy
 */
public class PathUtils {

    private static final Logger logger = LoggerFactory.getLogger(PathUtils.class);

    /**
     * 将路径转换为安全的目录名：特殊字符替换为 -，去掉首尾 -。
     */
    public static String sanitizePath(String path) {
        return path.replaceAll("[^a-zA-Z0-9._-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    /**
     * 旧的 MD5 前 4 字节 hash 算法（用于迁移时定位旧目录）。
     */
    public static String legacyHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return input.replaceAll("[^a-zA-Z0-9]", "_");
        }
    }

    /** 已知的索引文件名列表，迁移后需要修正其中的路径引用 */
    private static final String[] INDEX_FILES = {"MEMORY_INDEX.json"};

    /**
     * 修正迁移后索引文件中的旧路径引用。
     */
    private static void fixIndexFilePaths(Path newDir, String oldPrefix, String newPrefix) {
        for (String indexFile : INDEX_FILES) {
            Path path = newDir.resolve(indexFile);
            if (!Files.exists(path)) continue;
            try {
                String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                String fixed = content.replace(oldPrefix, newPrefix);
                if (!fixed.equals(content)) {
                    Files.write(path, fixed.getBytes(StandardCharsets.UTF_8));
                    logger.info("索引文件路径已修正: {}", path);
                }
            } catch (IOException e) {
                logger.warn("修正索引文件路径失败: {}", path, e);
            }
        }
    }

    /**
     * 迁移指定 baseDir 下的旧 hash 目录到新路径命名目录。
     * <p>
     * 扫描 baseDir 下所有子目录，如果目录名匹配 8 位十六进制（旧 hash 格式），
     * 则根据 keyResolver 提供的映射关系进行迁移。
     *
     * @param baseDir     基础目录（如 ~/.cmd-proxy/memory）
     * @param knownKeys   已知的 key 列表（workspacePath 或 robotName），用于计算旧 hash 并匹配
     */
    public static void migrateHashDirs(String baseDir, Iterable<String> knownKeys) {
        Path base = Paths.get(baseDir);
        if (!Files.exists(base)) return;

        for (String key : knownKeys) {
            String oldHash = legacyHash(key);
            String newName = sanitizePath(key);
            Path oldDir = base.resolve(oldHash);
            Path newDir = base.resolve(newName);

            if (!Files.exists(oldDir) || !Files.isDirectory(oldDir)) continue;
            if (Files.exists(newDir)) {
                logger.info("迁移目标已存在，跳过: {} -> {}", oldDir, newDir);
                continue;
            }

            try {
                Files.move(oldDir, newDir);
                logger.info("数据迁移完成: {} -> {}", oldDir, newDir);
                fixIndexFilePaths(newDir, oldDir.toString(), newDir.toString());
            } catch (IOException e) {
                logger.error("数据迁移失败: {} -> {}", oldDir, newDir, e);
            }
        }
    }
}
