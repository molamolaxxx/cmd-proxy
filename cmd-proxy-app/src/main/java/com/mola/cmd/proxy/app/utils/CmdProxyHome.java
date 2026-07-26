package com.mola.cmd.proxy.app.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * cmd-proxy 数据根目录（“环境”）的统一解析入口。
 * <p>
 * 一台主机上可以并行运行多套环境，每套环境拥有独立的数据根目录，
 * 内部结构（acpConfig.json / memory / session / schedules / ability / skills / mcp.json ...）保持不变。
 * <p>
 * 解析优先级：
 * <ol>
 *   <li>环境变量 {@code CMD_PROXY_HOME}</li>
 *   <li>系统属性 {@code -Dcmd.proxy.home}</li>
 *   <li>默认 {@code ~/.cmd-proxy}</li>
 * </ol>
 * 端口同理支持覆盖，避免多环境并行时抢占同一端口：
 * <ul>
 *   <li>RPC 端口：{@code CMD_PROXY_RPC_PORT} / {@code -Dcmd.proxy.rpcPort}，默认 10020</li>
 *   <li>ConfigUI 默认端口：{@code CMD_PROXY_CONFIG_UI_PORT} / {@code -Dcmd.proxy.configUiPort}，默认 10528
 *       （仅用于首次生成配置文件时的默认值，之后由 acpConfig.json 的 configUi.port 决定）</li>
 * </ul>
 */
public final class CmdProxyHome {

    private static final Logger logger = LoggerFactory.getLogger(CmdProxyHome.class);

    public static final String ENV_HOME = "CMD_PROXY_HOME";
    public static final String PROP_HOME = "cmd.proxy.home";
    public static final String ENV_RPC_PORT = "CMD_PROXY_RPC_PORT";
    public static final String PROP_RPC_PORT = "cmd.proxy.rpcPort";
    public static final String ENV_CONFIG_UI_PORT = "CMD_PROXY_CONFIG_UI_PORT";
    public static final String PROP_CONFIG_UI_PORT = "cmd.proxy.configUiPort";

    public static final int DEFAULT_RPC_PORT = 10020;
    public static final int DEFAULT_CONFIG_UI_PORT = 10528;

    /** 默认目录名（相对 user.home） */
    private static final String DEFAULT_DIR_NAME = ".cmd-proxy";

    private static final Path HOME = resolveHome();
    private static final int RPC_PORT = resolvePort(ENV_RPC_PORT, PROP_RPC_PORT, DEFAULT_RPC_PORT);
    private static final int CONFIG_UI_PORT =
            resolvePort(ENV_CONFIG_UI_PORT, PROP_CONFIG_UI_PORT, DEFAULT_CONFIG_UI_PORT);

    private CmdProxyHome() {
    }

    /** 数据根目录（绝对路径，无尾部分隔符） */
    public static Path dir() {
        return HOME;
    }

    /** 数据根目录的绝对路径字符串 */
    public static String path() {
        return HOME.toString();
    }

    /** 拼接数据根目录下的子路径 */
    public static Path resolve(String... more) {
        Path p = HOME;
        for (String segment : more) {
            p = p.resolve(segment);
        }
        return p;
    }

    /** 拼接数据根目录下的子路径，返回字符串 */
    public static String pathOf(String... more) {
        return resolve(more).toString();
    }

    /** RPC（mola-rpc）本地端口 */
    public static int rpcPort() {
        return RPC_PORT;
    }

    /** ConfigUI 默认端口，仅用于首次初始化配置文件 */
    public static int configUiPort() {
        return CONFIG_UI_PORT;
    }

    /**
     * 环境标识：由数据根目录路径归一化而来，用于主机级实例注册表的文件名与日志。
     * 例如 /home/mola/.cmd-proxy → home-mola-.cmd-proxy
     */
    public static String instanceId() {
        return sanitize(HOME.toString());
    }

    /** 是否为默认环境（~/.cmd-proxy） */
    public static boolean isDefault() {
        return HOME.equals(defaultHome());
    }

    /** 打印当前环境信息，便于多环境排障 */
    public static void logSummary() {
        logger.info("cmd-proxy 环境: home={}, instanceId={}, rpcPort={}, configUiDefaultPort={}, default={}",
                path(), instanceId(), rpcPort(), configUiPort(), isDefault());
    }

    private static Path defaultHome() {
        return Paths.get(System.getProperty("user.home"), DEFAULT_DIR_NAME).toAbsolutePath().normalize();
    }

    private static Path resolveHome() {
        String configured = firstNonBlank(System.getenv(ENV_HOME), System.getProperty(PROP_HOME));
        Path home = configured != null
                ? Paths.get(expandUserHome(configured.trim())).toAbsolutePath().normalize()
                : defaultHome();
        File dir = home.toFile();
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IllegalStateException("无法创建 cmd-proxy 数据根目录: " + home);
        }
        if (dir.exists() && !dir.isDirectory()) {
            throw new IllegalStateException("cmd-proxy 数据根目录不是目录: " + home);
        }
        return home;
    }

    /** 支持 ~ 与 ~/xxx 形式 */
    private static String expandUserHome(String raw) {
        if ("~".equals(raw)) {
            return System.getProperty("user.home");
        }
        if (raw.startsWith("~/") || raw.startsWith("~\\")) {
            return System.getProperty("user.home") + raw.substring(1);
        }
        return raw;
    }

    private static int resolvePort(String envKey, String propKey, int defaultPort) {
        String raw = firstNonBlank(System.getenv(envKey), System.getProperty(propKey));
        if (raw == null) {
            return defaultPort;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            if (port <= 0 || port > 65535) {
                logger.warn("端口配置非法，回退默认值: key={}, value={}, default={}", envKey, raw, defaultPort);
                return defaultPort;
            }
            return port;
        } catch (NumberFormatException e) {
            logger.warn("端口配置无法解析，回退默认值: key={}, value={}, default={}", envKey, raw, defaultPort);
            return defaultPort;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** 归一化路径为安全文件名：特殊字符替换为 -，去掉首尾 - */
    private static String sanitize(String path) {
        return path.replaceAll("[^a-zA-Z0-9._-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }
}
