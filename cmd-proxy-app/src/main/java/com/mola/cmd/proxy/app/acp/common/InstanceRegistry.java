package com.mola.cmd.proxy.app.acp.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 主机级实例注册表，用于同一主机上并行运行多套 cmd-proxy 环境时的冲突防御。
 * <p>
 * 每个实例在 {@code ~/.cmd-proxy-instances/{instanceId}.json} 登记自己的
 * 数据根目录、端口、chatterIds 与 robot 名称，并对该文件持有排他 {@link FileLock}
 * 直到进程退出。存活判定靠“能否抢到锁”，因此崩溃残留的记录会被自动回收，无需 pid 探测。
 * <p>
 * 拦截以下会造成静默数据损坏的组合：
 * <ol>
 *   <li><b>chatterIds 集合完全相同</b>：MolaChat 的 {@code acpSyncRobots}
 *       按 visibleChatterIds 精确相等划定作用域并全量覆盖，作用域相同的两个实例
 *       会互相删除对方的 robot 及其会话。</li>
 *   <li><b>robot 名称重叠</b>：robotId = {@code acp-{name}}、
 *       groupId = {@code sorted(chatterId + robotId)}，重名会让两个实例注册同一
 *       group，消息路由不确定。</li>
 *   <li><b>数据根目录被另一进程占用</b>：同一环境被重复启动。</li>
 * </ol>
 * 端口冲突不在此拦截：{@code PortAllocator} 会结合 {@link #reservedPorts()} 自动避让，
 * 登记后若仍发现端口相同（探测竞态）只告警。
 */
public final class InstanceRegistry {

    private static final Logger logger = LoggerFactory.getLogger(InstanceRegistry.class);

    /** 注册表目录覆盖项（默认 ~/.cmd-proxy-instances，必须是主机级、不随环境变化） */
    public static final String ENV_REGISTRY_DIR = "CMD_PROXY_INSTANCE_REGISTRY";
    public static final String PROP_REGISTRY_DIR = "cmd.proxy.instanceRegistry";

    private static final String DEFAULT_REGISTRY_DIR_NAME = ".cmd-proxy-instances";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static FileChannel ownChannel;
    private static FileLock ownLock;
    private static Path ownFile;
    private static boolean shutdownHookRegistered;
    /** 最近一次登记的自身信息，供 listAll 展示 */
    private static Entry lastSelf;

    private InstanceRegistry() {
    }

    /**
     * 抢占当前环境的所有权（对注册文件加排他锁）。
     * 应在启动早期调用，使“同一环境重复启动”尽早失败。
     *
     * @throws IllegalStateException 该数据根目录已被另一个进程占用
     */
    public static synchronized void acquireOwnership() {
        Path dir = registryDir();
        if (!ensureDir(dir)) {
            return;
        }
        acquireOwnLock(dir, CmdProxyHome.instanceId(), CmdProxyHome.path());
    }

    /**
     * 其它存活实例已登记的端口集合，供端口自动分配跳过，避免探测竞态。
     */
    public static synchronized Set<Integer> reservedPorts() {
        Path dir = registryDir();
        if (!ensureDir(dir)) {
            return Collections.emptySet();
        }
        Set<Integer> ports = new LinkedHashSet<>();
        for (Entry other : scanAliveOthers(dir)) {
            if (other.rpcPort > 0) {
                ports.add(other.rpcPort);
            }
            if (other.configUiPort > 0) {
                ports.add(other.configUiPort);
            }
        }
        return ports;
    }

    /**
     * 主机上所有存活实例（含自身），自身排在首位。
     * 供 ConfigUI 展示环境列表与跨环境代理寻址。
     */
    public static synchronized List<InstanceInfo> listAll() {
        List<InstanceInfo> result = new ArrayList<>();
        result.add(selfInfo());
        Path dir = registryDir();
        if (!ensureDir(dir)) {
            return result;
        }
        for (Entry other : scanAliveOthers(dir)) {
            result.add(toInfo(other, false));
        }
        return result;
    }

    /** 当前实例信息；尚未登记时用 CmdProxyHome 的解析结果兜底 */
    private static InstanceInfo selfInfo() {
        if (lastSelf != null) {
            return toInfo(lastSelf, true);
        }
        Entry entry = new Entry();
        entry.instanceId = CmdProxyHome.instanceId();
        entry.home = CmdProxyHome.path();
        entry.rpcPort = CmdProxyHome.rpcPort();
        entry.configUiPort = CmdProxyHome.configUiPort();
        return toInfo(entry, true);
    }

    private static InstanceInfo toInfo(Entry entry, boolean self) {
        InstanceInfo info = new InstanceInfo();
        info.instanceId = entry.instanceId;
        info.home = entry.home;
        info.rpcPort = entry.rpcPort;
        info.configUiPort = entry.configUiPort;
        info.chatterIds = new LinkedHashSet<>(entry.chatterIds);
        info.robotNames = new LinkedHashSet<>(entry.robotNames);
        info.self = self;
        return info;
    }

    /**
     * 校验并登记当前实例。启动与每次热重载都应调用，登记内容会被覆盖为最新值。
     *
     * @param chatterIds   当前环境配置的 chatterId 集合
     * @param robotNames   当前环境启用的 robot 名称集合
     * @param rpcPort      实际使用的 RPC 端口
     * @param configUiPort 实际使用的 ConfigUI 端口
     * @throws IllegalStateException 检测到致命冲突时抛出，调用方应终止启动或拒绝本次重载
     */
    public static synchronized void checkAndRegister(Collection<String> chatterIds,
                                                     Collection<String> robotNames,
                                                     int rpcPort,
                                                     int configUiPort) {
        Path dir = registryDir();
        if (!ensureDir(dir)) {
            return;
        }

        Entry self = new Entry();
        self.instanceId = CmdProxyHome.instanceId();
        self.home = CmdProxyHome.path();
        self.rpcPort = rpcPort;
        self.configUiPort = configUiPort;
        self.chatterIds = normalize(chatterIds);
        self.robotNames = normalize(robotNames);
        self.updateTime = System.currentTimeMillis();

        acquireOwnLock(dir, self.instanceId, self.home);

        List<String> fatal = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        for (Entry other : scanAliveOthers(dir)) {
            collectConflicts(self, other, fatal, warns);
        }
        for (String warn : warns) {
            logger.warn("多环境告警: {}", warn);
        }
        if (!fatal.isEmpty()) {
            throw new IllegalStateException(buildFatalMessage(self, fatal));
        }

        writeOwn(self);
        lastSelf = self;
        logger.info("实例已登记: home={}, rpcPort={}, configUiPort={}, chatterIds={}, robots={}",
                self.home, self.rpcPort, self.configUiPort, self.chatterIds, self.robotNames);
    }

    private static boolean ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
            return true;
        } catch (IOException e) {
            logger.warn("实例注册表目录创建失败，跳过多环境冲突检查: dir={}", dir, e);
            return false;
        }
    }

    private static void collectConflicts(Entry self, Entry other, List<String> fatal, List<String> warns) {
        // 端口已由 PortAllocator 自动避让，走到这里说明存在探测竞态，只告警不阻断启动
        if (self.rpcPort == other.rpcPort) {
            warns.add(String.format("RPC 端口 %d 与环境 [%s] 相同，可能存在端口分配竞态", self.rpcPort, other.home));
        }
        if (self.configUiPort == other.configUiPort) {
            warns.add(String.format("ConfigUI 端口 %d 与环境 [%s] 相同，可能存在端口分配竞态",
                    self.configUiPort, other.home));
        }
        boolean selfActive = !self.chatterIds.isEmpty() && !self.robotNames.isEmpty();
        boolean otherActive = !other.chatterIds.isEmpty() && !other.robotNames.isEmpty();
        if (!selfActive || !otherActive) {
            return;
        }
        if (self.chatterIds.equals(other.chatterIds)) {
            fatal.add(String.format("chatterIds %s 与环境 [%s] 完全相同。MolaChat 的 acpSyncRobots 按 chatterIds "
                            + "精确相等划定作用域并全量覆盖，两个实例会互相删除对方的 robot 及历史会话。"
                            + "请让两套环境的 chatterIds 集合不相等（例如其中一套追加一个占位 chatterId）",
                    self.chatterIds, other.home));
        }
        Set<String> dupRobots = new TreeSet<>(self.robotNames);
        dupRobots.retainAll(other.robotNames);
        if (!dupRobots.isEmpty()) {
            fatal.add(String.format("robot 名称与环境 [%s] 重复: %s。robotId=acp-{name}、"
                            + "groupId=sorted(chatterId+robotId)，重名会导致消息路由到错误的实例，请重命名",
                    other.home, dupRobots));
        }
    }

    private static String buildFatalMessage(Entry self, List<String> fatal) {
        StringBuilder sb = new StringBuilder();
        sb.append("检测到多环境冲突，已阻止启动（环境: ").append(self.home).append("）:");
        for (int i = 0; i < fatal.size(); i++) {
            sb.append("\n  ").append(i + 1).append(". ").append(fatal.get(i));
        }
        return sb.toString();
    }

    /** 对自己的注册文件加排他锁；已持有则直接复用 */
    private static void acquireOwnLock(Path dir, String instanceId, String home) {
        if (ownLock != null && ownLock.isValid()) {
            return;
        }
        Path file = dir.resolve(instanceId + ".json");
        try {
            FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock lock = null;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                // 同 JVM 内重复加锁，理论上不会发生（已由 ownLock 判空拦截）
                lock = null;
            }
            if (lock == null) {
                closeQuietly(channel);
                throw new IllegalStateException("数据根目录 " + home
                        + " 已被另一个 cmd-proxy 进程占用，请勿对同一环境重复启动；"
                        + "如需并行多套环境，请用 " + CmdProxyHome.ENV_HOME + " 指定不同目录");
            }
            ownChannel = channel;
            ownLock = lock;
            ownFile = file;
            registerShutdownHook();
        } catch (IOException e) {
            logger.warn("实例注册文件加锁失败，跳过多环境冲突检查: file={}", file, e);
        }
    }

    /** 扫描其它存活实例：能抢到锁的记录视为残留并清理 */
    private static List<Entry> scanAliveOthers(Path dir) {
        List<Entry> alive = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                if (ownFile != null && file.getFileName().equals(ownFile.getFileName())) {
                    continue;
                }
                if (isStale(file)) {
                    try {
                        Files.deleteIfExists(file);
                        logger.info("清理残留实例记录: {}", file.getFileName());
                    } catch (IOException e) {
                        logger.warn("残留实例记录清理失败: {}", file, e);
                    }
                    continue;
                }
                Entry entry = readEntry(file);
                if (entry != null) {
                    alive.add(entry);
                }
            }
        } catch (IOException e) {
            logger.warn("实例注册表扫描失败，跳过多环境冲突检查: dir={}", dir, e);
        }
        return alive;
    }

    /** 能拿到排他锁说明持有者已退出 */
    private static boolean isStale(Path file) {
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                return false;
            }
            if (lock == null) {
                return false;
            }
            lock.release();
            return true;
        } catch (IOException e) {
            logger.warn("实例存活判定失败，按存活处理: file={}", file, e);
            return false;
        }
    }

    private static Entry readEntry(Path file) {
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Entry entry = GSON.fromJson(json, Entry.class);
            if (entry == null) {
                return null;
            }
            if (entry.home == null) {
                entry.home = file.getFileName().toString();
            }
            if (entry.chatterIds == null) {
                entry.chatterIds = new LinkedHashSet<>();
            }
            if (entry.robotNames == null) {
                entry.robotNames = new LinkedHashSet<>();
            }
            return entry;
        } catch (Exception e) {
            logger.warn("实例记录解析失败，忽略: file={}", file, e);
            return null;
        }
    }

    private static void writeOwn(Entry self) {
        if (ownChannel == null || !ownChannel.isOpen()) {
            return;
        }
        try {
            byte[] bytes = GSON.toJson(self).getBytes(StandardCharsets.UTF_8);
            ownChannel.truncate(0);
            ownChannel.position(0);
            ownChannel.write(java.nio.ByteBuffer.wrap(bytes));
            ownChannel.force(true);
        } catch (IOException e) {
            logger.warn("实例记录写入失败: file={}", ownFile, e);
        }
    }

    private static synchronized void release() {
        try {
            if (ownLock != null && ownLock.isValid()) {
                ownLock.release();
            }
        } catch (IOException ignored) {
            // 进程退出中，忽略
        }
        closeQuietly(ownChannel);
        if (ownFile != null) {
            try {
                Files.deleteIfExists(ownFile);
            } catch (IOException ignored) {
                // 残留记录会在下次启动时按“可加锁”判定清理
            }
        }
        ownLock = null;
        ownChannel = null;
        ownFile = null;
    }

    private static void registerShutdownHook() {
        if (shutdownHookRegistered) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(InstanceRegistry::release, "instance-registry-release"));
        shutdownHookRegistered = true;
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // ignore
        }
    }

    private static Path registryDir() {
        String configured = System.getenv(ENV_REGISTRY_DIR);
        if (configured == null || configured.trim().isEmpty()) {
            configured = System.getProperty(PROP_REGISTRY_DIR);
        }
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured.trim()).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), DEFAULT_REGISTRY_DIR_NAME)
                .toAbsolutePath().normalize();
    }

    private static Set<String> normalize(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                set.add(value.trim());
            }
        }
        return set;
    }

    /** 注册表记录 */
    private static class Entry {
        String instanceId;
        String home;
        int rpcPort;
        int configUiPort;
        Set<String> chatterIds = new LinkedHashSet<>();
        Set<String> robotNames = new LinkedHashSet<>();
        long updateTime;
    }

    /** 对外暴露的实例信息（ConfigUI 环境列表与跨环境代理寻址） */
    public static class InstanceInfo {
        public String instanceId;
        public String home;
        public int rpcPort;
        /** ConfigUI 端口，0 表示该环境未开启配置页，无法远程编辑 */
        public int configUiPort;
        public Set<String> chatterIds = new LinkedHashSet<>();
        public Set<String> robotNames = new LinkedHashSet<>();
        /** 是否为当前进程所属环境 */
        public boolean self;
    }
}
