package com.mola.cmd.proxy.app.acp.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 路径工具类，提供统一的目录命名能力。
 * <p>
 * 命名规则：将路径中的 / 等特殊字符替换为 -，去掉首尾 -。
 * 例如 /home/mola/IdeaProjects/cmd-proxy → home-mola-IdeaProjects-cmd-proxy
 */
public class PathUtils {

    /**
     * 将路径转换为安全的目录名：特殊字符替换为 -，去掉首尾 -。
     * <p>
     * 为保持已有目录兼容，只在旧规则得到空名时使用原文的 SHA-256。
     * 这使纯中文等 Unicode 名称也能稳定映射到非空、可移植的目录名。
     */
    public static String sanitizePath(String path) {
        String sanitized = path.replaceAll("[^a-zA-Z0-9._-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (!sanitized.isEmpty()) {
            return sanitized;
        }
        return "unicode-" + sha256(path);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 Java 平台必须支持的算法。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
