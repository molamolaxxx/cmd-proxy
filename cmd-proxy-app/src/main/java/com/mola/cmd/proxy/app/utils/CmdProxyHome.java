package com.mola.cmd.proxy.app.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

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
    public static final String ENV_INSTANCE_ID = "CMD_PROXY_INSTANCE_ID";
    public static final String PROP_INSTANCE_ID = "cmd.proxy.instanceId";
    public static final String ENV_RPC_PORT = "CMD_PROXY_RPC_PORT";
    public static final String PROP_RPC_PORT = "cmd.proxy.rpcPort";
    public static final String ENV_CONFIG_UI_PORT = "CMD_PROXY_CONFIG_UI_PORT";
    public static final String PROP_CONFIG_UI_PORT = "cmd.proxy.configUiPort";

    public static final int DEFAULT_RPC_PORT = 10020;
    public static final int DEFAULT_CONFIG_UI_PORT = 10528;

    /** 默认目录名（相对 user.home） */
    private static final String DEFAULT_DIR_NAME = ".cmd-proxy";
    private static final String INSTANCE_ID_FILE = "instance-id";

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
     * 跨机器稳定的环境标识。显式配置优先；否则首次根据机器名与数据根目录生成，
     * 并持久化到 {@code $CMD_PROXY_HOME/instance-id}，避免重启后漂移。
     */
    public static String instanceId() {
        return InstanceIdHolder.INSTANCE_ID;
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

    private static String resolveInstanceId(Path home) {
        String configured = firstNonBlank(
                System.getenv(ENV_INSTANCE_ID), System.getProperty(PROP_INSTANCE_ID));
        return resolveInstanceId(home, configured, null);
    }

    static String resolveInstanceId(Path home, String configured, String machineNameOverride) {
        Path file = home.resolve(INSTANCE_ID_FILE);
        if (configured != null) {
            String explicit = requireSafeInstanceId(configured, ENV_INSTANCE_ID);
            persistInstanceId(file, explicit, true);
            return explicit;
        }
        String persisted = readPersistedInstanceId(file);
        if (persisted != null) {
            return persisted;
        }
        String generated = requireSafeInstanceId(
                (machineNameOverride == null ? machineName() : machineNameOverride)
                        + "-" + sanitize(home.toString()), "generated instanceId");
        persistInstanceId(file, generated, false);
        String winner = readPersistedInstanceId(file);
        return winner == null ? generated : winner;
    }

    private static String readPersistedInstanceId(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String value = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            return requireSafeInstanceId(value, file.toString());
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 cmd-proxy instanceId: " + file, e);
        }
    }

    private static void persistInstanceId(Path file, String instanceId, boolean replace) {
        try {
            if (replace) {
                Files.write(file, (instanceId + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } else {
                Files.write(file, (instanceId + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
        } catch (FileAlreadyExistsException ignored) {
            // 同一数据根并发启动时，使用先完成持久化的实例标识。
        } catch (IOException e) {
            throw new IllegalStateException("无法持久化 cmd-proxy instanceId: " + file, e);
        }
    }

    private static String machineName() {
        try {
            String hostName = InetAddress.getLocalHost().getHostName();
            if (hostName != null && !hostName.trim().isEmpty()) {
                return sanitize(hostName);
            }
        } catch (UnknownHostException e) {
            logger.warn("无法通过 InetAddress 获取机器名，将尝试环境变量", e);
        }
        String environmentName = firstNonBlank(
                System.getenv("HOSTNAME"), System.getenv("COMPUTERNAME"));
        if (environmentName != null) {
            return sanitize(environmentName);
        }
        throw new IllegalStateException("无法确定机器名，请配置 " + ENV_INSTANCE_ID);
    }

    private static String requireSafeInstanceId(String value, String source) {
        String normalized = sanitize(value == null ? "" : value.trim());
        if (normalized.isEmpty() || ".".equals(normalized) || "..".equals(normalized)) {
            throw new IllegalStateException("cmd-proxy instanceId 非法: source=" + source);
        }
        return normalized;
    }

    private static final class InstanceIdHolder {
        private static final String INSTANCE_ID = resolveInstanceId(HOME);
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
