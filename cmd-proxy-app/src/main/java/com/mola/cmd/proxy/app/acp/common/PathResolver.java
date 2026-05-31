package com.mola.cmd.proxy.app.acp.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 环境变量 PATH 解析工具。
 * <p>
 * 通过用户 login shell 获取完整 PATH（加载 .bashrc/.zshrc/.profile 等），
 * 解决从 IDE / systemd 服务等精简环境启动时 PATH 不完整的问题。
 * 结果按 user.home 缓存，避免每次启动子进程都重复执行 shell。
 * <p>
 * 兼容 Linux 和 macOS，通过 $SHELL 环境变量推断 shell，
 * 超时或失败时回退到原始 PATH。
 */
public class PathResolver {

    private static final Logger logger = LoggerFactory.getLogger(PathResolver.class);

    /** 缓存 key = user.home，value = login shell 解析出的完整 PATH */
    private static final ConcurrentHashMap<String, String> SHELL_PATH_CACHE = new ConcurrentHashMap<>();

    /** shell 执行超时（秒） */
    private static final long SHELL_TIMEOUT_SECONDS = 3;

    /** 前置追加的固定路径，确保关键工具目录优先命中 */
    private static final String EXTRA_PATHS_TEMPLATE = "%s/.local/bin" + File.pathSeparator
            + "%s/.cargo/bin" + File.pathSeparator
            + "/usr/local/bin";

    /**
     * 获取经过增强的 PATH。
     * <p>
     * 优先通过用户 login shell 解析，再前置追加 .local/bin、.cargo/bin 等常用路径。
     *
     * @param home        用户 home 目录
     * @param inheritedPath JVM 进程继承的 PATH（作为回退值）
     * @return 增强后的 PATH 字符串
     */
    public static String enrichPath(String home, String inheritedPath) {
        String extraPaths = String.format(EXTRA_PATHS_TEMPLATE, home, home);

        // 尝试从 login shell 获取完整 PATH
        String shellPath = resolveShellPath(home);

        String basePath;
        if (shellPath != null && !shellPath.isEmpty()) {
            basePath = shellPath;
            logger.debug("使用 login shell PATH (len={}), inherited PATH (len={})",
                    shellPath.length(), inheritedPath.length());
        } else {
            basePath = inheritedPath;
            logger.debug("login shell PATH 获取失败，回退到 inherited PATH (len={})",
                    inheritedPath.length());
        }

        // 前置追加固定路径，去重
        if (basePath != null && basePath.contains(home + "/.local/bin")) {
            return basePath;
        }
        return extraPaths + File.pathSeparator + basePath;
    }

    /**
     * 通过用户 login shell 解析 PATH，结果缓存。
     * <p>
     * 支持 bash / zsh / fish：
     * <ul>
     *   <li>bash: {@code bash -l -c 'echo $PATH'}</li>
     *   <li>zsh:  {@code zsh -l -c 'echo $PATH'}</li>
     *   <li>fish: {@code fish -l -c 'echo $PATH'}</li>
     *   <li>未知: 从 $SHELL 推断，默认 /bin/bash</li>
     * </ul>
     *
     * @param home 用户 home 目录，作为缓存 key
     * @return 解析出的 PATH，失败返回 null
     */
    private static String resolveShellPath(String home) {
        return SHELL_PATH_CACHE.computeIfAbsent(home, k -> {
            String shell = resolveShell();
            try {
                ProcessBuilder pb = new ProcessBuilder(shell, "-l", "-c", "echo $PATH");
                pb.redirectErrorStream(true);

                Process p = pb.start();
                boolean finished = p.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                if (!finished) {
                    p.destroyForcibly();
                    logger.warn("login shell ({}) 执行超时 ({}s)，已强制终止", shell, SHELL_TIMEOUT_SECONDS);
                    return null;
                }

                if (p.exitValue() != 0) {
                    logger.warn("login shell ({}) 退出码非 0: {}", shell, p.exitValue());
                    return null;
                }

                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String path = r.readLine();
                    if (path != null && !path.isEmpty()) {
                        logger.info("从 login shell ({}) 解析到 PATH，长度={}", shell, path.length());
                        return path.trim();
                    }
                }
            } catch (Exception e) {
                logger.warn("通过 login shell ({}) 获取 PATH 失败: {}", shell, e.getMessage());
            }
            return null;
        });
    }

    /**
     * 推断用户的登录 shell。
     * 从 $SHELL 环境变量读取，如果为空或不存在则默认 /bin/bash。
     */
    static String resolveShell() {
        String shell = System.getenv("SHELL");
        if (shell != null && !shell.isEmpty()) {
            return shell;
        }
        return "/bin/bash";
    }

    /**
     * 清除 PATH 缓存（主要用于测试）。
     */
    static void clearCache() {
        SHELL_PATH_CACHE.clear();
    }
}
