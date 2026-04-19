package com.mola.cmd.proxy.app.acp.ability;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.common.PathUtils;
import com.mola.cmd.proxy.app.acp.memory.MemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * 能力反思服务，独立于记忆系统。
 * <p>
 * 结合 skills（kiro-cli 自动注入）、记忆概要和 MCP 工具列表，
 * 让 LLM 反思自身能力，将结果持久化到 .cmd-proxy/ability/{robot-name-hash}/ability.md。
 * <p>
 * 触发时机：AcpClient 初始化时调用 {@link #submitReflection()}，内部判断是否需要执行：
 * <ul>
 *   <li>ability.md 不存在 → 首次反思</li>
 *   <li>skills 文件内容变动</li>
 *   <li>MCP 工具配置变动</li>
 *   <li>上次反思之后发生过 auto dream</li>
 * </ul>
 * 变动判断基于快照文件 snapshot.json，记录上次反思时的各项指纹。
 */
public class AbilityReflectionService {

    private static final Logger logger = LoggerFactory.getLogger(AbilityReflectionService.class);
    private static final String BASE_DIR = System.getProperty("user.home") + "/.cmd-proxy/ability";
    private static final String ABILITY_FILE = "ability.md";
    private static final String SNAPSHOT_FILE = "snapshot.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String robotName;
    private final String workspacePath;
    private final String agentProvider;
    private final int timeoutSeconds;
    private final List<Path> mcpConfigPaths;
    /** 记忆管理器，可为 null（未启用记忆时） */
    private final MemoryManager memoryManager;

    private final ExecutorService queue = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(2),
            r -> {
                Thread t = new Thread(r, "ability-reflection-queue");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    public AbilityReflectionService(String robotName, String workspacePath,
                                    String agentProvider, int timeoutSeconds,
                                    List<Path> mcpConfigPaths,
                                    MemoryManager memoryManager) {
        this.robotName = robotName;
        this.workspacePath = workspacePath;
        this.agentProvider = agentProvider;
        this.timeoutSeconds = timeoutSeconds;
        this.mcpConfigPaths = mcpConfigPaths != null ? mcpConfigPaths : Collections.emptyList();
        this.memoryManager = memoryManager;
    }

    // ==================== 触发入口 ====================

    /**
     * 提交能力反思任务。内部判断是否需要执行：
     * <ol>
     *   <li>ability.md 不存在 → 首次反思</li>
     *   <li>skills 内容指纹变动</li>
     *   <li>MCP 工具列表变动</li>
     *   <li>上次反思之后发生过 auto dream</li>
     * </ol>
     * 不满足任何条件时静默跳过。
     */
    public void submitReflection() {
        try {
            queue.submit(this::checkAndReflect);
        } catch (RejectedExecutionException e) {
            logger.warn("能力反思队列已满，跳过本次反思");
        }
    }

    private void checkAndReflect() {
        try {
            Path abilityFile = getAbilityFilePath();
            if (!Files.exists(abilityFile)) {
                logger.info("ability.md 不存在，触发首次能力反思, robotName={}", robotName);
                doReflection();
                return;
            }

            // 加载上次快照
            Snapshot lastSnapshot = loadSnapshot();
            if (lastSnapshot == null) {
                logger.info("快照不存在，触发能力反思, robotName={}", robotName);
                doReflection();
                return;
            }

            // 构建当前指纹
            String currentSkillsHash = computeSkillsHash();
            List<String> currentMcpServers = loadMcpServerNames();
            String currentMcpHash = computeHash(String.join(",", currentMcpServers));
            String currentDreamTime = getLastDreamTime();

            boolean skillsChanged = !currentSkillsHash.equals(lastSnapshot.skillsHash);
            boolean mcpChanged = !currentMcpHash.equals(lastSnapshot.mcpHash);
            boolean dreamOccurred = currentDreamTime != null
                    && !currentDreamTime.equals(lastSnapshot.lastDreamTime);

            if (skillsChanged || mcpChanged || dreamOccurred) {
                logger.info("检测到变动，触发能力反思, robotName={}, skills={}, mcp={}, dream={}",
                        robotName, skillsChanged, mcpChanged, dreamOccurred);
                doReflection();
            } else {
                logger.debug("无变动，跳过能力反思, robotName={}", robotName);
            }
        } catch (Exception e) {
            logger.error("能力反思检查失败, robotName={}", robotName, e);
        }
    }

    // ==================== 核心反思逻辑 ====================

    private void doReflection() {
        logger.info("开始能力反思, robotName={}, workspacePath={}", robotName, workspacePath);
        try {
            List<String> mcpServerNames = loadMcpServerNames();
            String memorySummary = "";
            if (memoryManager != null) {
                try {
                    memorySummary = memoryManager.buildMemorySummary(workspacePath);
                } catch (Exception e) {
                    logger.warn("获取记忆概要失败，跳过记忆部分", e);
                }
            }

            String prompt = AbilityReflectionPromptTemplate.build(mcpServerNames, memorySummary);
            String groupId = "ability_reflection__" + robotName.hashCode();

            String response;
            try (AbilityReflectionAcpClient client = new AbilityReflectionAcpClient(
                    workspacePath, groupId, timeoutSeconds, agentProvider)) {
                client.start();
                response = client.sendPromptSync(prompt);
            }

            if (response != null && !response.trim().isEmpty()) {
                writeAbilityFile(response.trim());
                saveSnapshot(mcpServerNames);
                logger.info("能力反思完成, robotName={}", robotName);
            } else {
                logger.warn("能力反思子 Client 返回为空, robotName={}", robotName);
            }
        } catch (Exception e) {
            logger.error("能力反思失败, robotName={}", robotName, e);
        }
    }

    // ==================== 快照管理 ====================

    /**
     * 快照模型，记录上次反思时的各项指纹。
     */
    private static class Snapshot {
        String skillsHash;
        String mcpHash;
        String lastDreamTime;
        String reflectionTime;
    }

    private Snapshot loadSnapshot() {
        Path path = getProjectDir().resolve(SNAPSHOT_FILE);
        if (!Files.exists(path)) return null;
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            return GSON.fromJson(content, Snapshot.class);
        } catch (Exception e) {
            logger.warn("加载快照失败: {}", path, e);
            return null;
        }
    }

    private void saveSnapshot(List<String> mcpServerNames) {
        Snapshot snapshot = new Snapshot();
        snapshot.skillsHash = computeSkillsHash();
        snapshot.mcpHash = computeHash(String.join(",", mcpServerNames));
        snapshot.lastDreamTime = getLastDreamTime();
        snapshot.reflectionTime = java.time.ZonedDateTime.now()
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Path path = getProjectDir().resolve(SNAPSHOT_FILE);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, GSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.error("保存快照失败: {}", path, e);
        }
    }

    // ==================== 指纹计算 ====================

    /**
     * 计算 .kiro/skills/ 目录下所有 skill 子目录名的聚合哈希。
     */
    private String computeSkillsHash() {
        Path skillsDir = Paths.get(workspacePath, ".kiro", "skills");
        if (!Files.exists(skillsDir) || !Files.isDirectory(skillsDir)) {
            return "empty";
        }
        try {
            TreeSet<String> parts = new TreeSet<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry)) {
                        Path skillFile = entry.resolve("SKILL.md");
                        if (Files.exists(skillFile)) {
                            String content = new String(Files.readAllBytes(skillFile), StandardCharsets.UTF_8);
                            parts.add(entry.getFileName().toString() + ":" + computeHash(content));
                        } else {
                            parts.add(entry.getFileName().toString());
                        }
                    }
                }
            }
            if (parts.isEmpty()) return "empty";
            return computeHash(parts.toString());
        } catch (IOException e) {
            logger.warn("计算 skills 哈希失败", e);
            return "error";
        }
    }

    /**
     * 获取最近一次 auto dream 的时间。
     * 通过读取 memory 系统的 DREAM_STATE.json 获取。
     */
    private String getLastDreamTime() {
        if (memoryManager == null) return null;
        try {
            // 通过 MemoryManager 暴露的 DreamState 获取
            return memoryManager.getLastDreamTime(workspacePath);
        } catch (Exception e) {
            logger.warn("获取 lastDreamTime 失败", e);
            return null;
        }
    }

    // ==================== MCP 配置解析 ====================

    private List<String> loadMcpServerNames() {
        List<String> names = new ArrayList<>();
        for (Path configPath : mcpConfigPaths) {
            if (!Files.exists(configPath)) continue;
            try {
                String content = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(content).getAsJsonObject();
                JsonObject servers = root.getAsJsonObject("mcpServers");
                if (servers == null) continue;
                for (Map.Entry<String, JsonElement> entry : servers.entrySet()) {
                    JsonObject serverObj = entry.getValue().getAsJsonObject();
                    if (serverObj.has("disabled") && serverObj.get("disabled").getAsBoolean()) continue;
                    if (!names.contains(entry.getKey())) {
                        names.add(entry.getKey());
                    }
                }
            } catch (Exception e) {
                logger.warn("读取 MCP 配置失败: {}", configPath, e);
            }
        }
        Collections.sort(names);
        return names;
    }

    // ==================== 文件与路径工具 ====================

    private void writeAbilityFile(String content) {
        Path filePath = getAbilityFilePath();
        try {
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));
            logger.info("ability.md 已写入: {}", filePath);
        } catch (IOException e) {
            logger.error("写入 ability.md 失败", e);
        }
    }

    private Path getAbilityFilePath() {
        return getProjectDir().resolve(ABILITY_FILE);
    }

    private Path getProjectDir() {
        return Paths.get(BASE_DIR, PathUtils.sanitizePath(workspacePath));
    }

    private String computeHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}
