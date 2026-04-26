package com.mola.cmd.proxy.app.acp.common;

/**
 * 路径工具类，提供统一的目录命名能力。
 * <p>
 * 命名规则：将路径中的 / 等特殊字符替换为 -，去掉首尾 -。
 * 例如 /home/mola/IdeaProjects/cmd-proxy → home-mola-IdeaProjects-cmd-proxy
 */
public class PathUtils {

    /**
     * 将路径转换为安全的目录名：特殊字符替换为 -，去掉首尾 -。
     */
    public static String sanitizePath(String path) {
        return path.replaceAll("[^a-zA-Z0-9._-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }
}
