package com.mola.cmd.proxy.app.acp.acpclient.context;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.common.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

/**
 * 会话上下文管理器，负责内存中的消息收集、磁盘落盘与加载。
 * <p>
 * 存储目录结构：
 * <pre>
 * ~/.cmd-proxy/session/{workspaceDirName}/{sessionId}/
 *   ├── turn_0000.json
 *   ├── turn_0001.json
 *   └── resources.json   (去重的图片 base64)
 * </pre>
 */
/**
 * 会话上下文管理器，负责内存中的消息收集、磁盘落盘与加载。
 * <p>
 * 存储目录结构：
 * <pre>
 * ~/.cmd-proxy/session/{workspaceDirName}/{sessionId}/
 *   ├── turn_0000.json
 *   ├── turn_0001.json
 *   └── files/          (用户上传的文件，按原始文件名存储)
 * </pre>
 */
public class ConversationHistoryManager {

    private static final Logger logger = LoggerFactory.getLogger(ConversationHistoryManager.class);
    private static final Path SESSION_ROOT_DIR = Paths.get(System.getProperty("user.home"), ".cmd-proxy", "session");
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 按 workspacePath 隔离的 session 基础目录 */
    private final Path sessionBaseDir;

    /** 当前 turn 的上下文消息，flushTurn 后清空 */
    private final List<ContextMessage> currentTurn = new ArrayList<>();

    /** 累积的文件绝对路径（去重） */
    private final LinkedHashSet<String> fileAbsolutePaths = new LinkedHashSet<>();

    /** turn 计数器 */
    private final AtomicInteger turnCounter = new AtomicInteger(0);

    /** turn 完成回调，用于每 N 轮触发记忆提取等外部逻辑 */
    private Runnable onTurnFlushed;

    /**
     * @param robotName 机器人名称，用于按 robot 隔离 session 存储
     */
    public ConversationHistoryManager(String robotName) {
        String dirName = PathUtils.sanitizePath(robotName);
        this.sessionBaseDir = SESSION_ROOT_DIR.resolve(dirName);
    }

    /**
     * 设置 turn 完成回调。每次 flushTurn 成功落盘后触发。
     */
    public void setOnTurnFlushed(Runnable callback) {
        this.onTurnFlushed = callback;
    }

    /**
     * 获取当前已完成的 turn 数。
     */
    public int getTurnCount() {
        return turnCounter.get();
    }

    // ==================== 消息收集 ====================

    /** 记录一条用户消息 */
    public void addUserMessage(String content) {
        currentTurn.add(new ContextMessage(ContextMessage.Role.USER, content));
    }

    /** 记录一条 agent 回答 */
    public void addAssistantMessage(String content) {
        currentTurn.add(new ContextMessage(ContextMessage.Role.ASSISTANT, content));
    }

    /** 记录一条工具调用结果 */
    public void addToolMessage(String toolCallId, String toolName, String status,
                               JsonObject rawInput, JsonObject rawOutput) {
        currentTurn.add(new ContextMessage(toolCallId, toolName, status, rawInput, rawOutput));
    }

    /**
     * 将文件 base64 内容写入会话的 files 目录，并记录绝对路径。
     *
     * @param sessionId 当前会话 ID
     * @param files     文件列表，每个 Map 的 key 为文件名，value 为文件的 base64 内容
     */
    public void saveFiles(String sessionId, List<Map<String, String>> files) {
        if (files == null || files.isEmpty() || sessionId == null) return;
        try {
            Path filesDir = sessionBaseDir.resolve(sessionId).resolve("files");
            Files.createDirectories(filesDir);
            for (Map<String, String> fileMap : files) {
                for (Map.Entry<String, String> entry : fileMap.entrySet()) {
                    String fileName = entry.getKey();
                    String base64Content = entry.getValue();
                    if (fileName == null || fileName.isEmpty() || base64Content == null) continue;
                    Path filePath = filesDir.resolve(fileName);
                    byte[] decoded = Base64.getDecoder().decode(base64Content);
                    Files.write(filePath, decoded);
                    fileAbsolutePaths.add(filePath.toAbsolutePath().toString());
                    logger.info("文件已保存: {}", filePath.toAbsolutePath());
                }
            }
        } catch (IOException e) {
            logger.error("文件保存失败, sessionId={}", sessionId, e);
        }
    }

    /**
     * 获取所有已保存文件的绝对路径集合（只读）。
     */
    public Set<String> getFileAbsolutePaths() {
        return Collections.unmodifiableSet(fileAbsolutePaths);
    }

    // ==================== 落盘 ====================

    /**
     * 将当前 turn 的消息落盘并清空内存。
     *
     * @param sessionId 当前会话 ID
     */
    public void flushTurn(String sessionId) {
        if (currentTurn.isEmpty() || sessionId == null) {
            return;
        }
        try {
            Path sessionDir = sessionBaseDir.resolve(sessionId);
            Files.createDirectories(sessionDir);

            int turn = turnCounter.getAndIncrement();
            Path turnFile = sessionDir.resolve(String.format("turn_%04d.json", turn));

            JsonArray array = new JsonArray();
            for (ContextMessage msg : currentTurn) {
                array.add(serializeMessage(msg));
            }

            Files.write(turnFile, PRETTY_GSON.toJson(array).getBytes(StandardCharsets.UTF_8));
            logger.info("会话上下文已落盘: {}, 消息数={}", turnFile, currentTurn.size());
        } catch (IOException e) {
            logger.error("会话上下文落盘失败, sessionId={}", sessionId, e);
        } finally {
            currentTurn.clear();
        }

        // 通知外部回调（如每 N 轮触发记忆提取）
        if (onTurnFlushed != null) {
            try {
                onTurnFlushed.run();
            } catch (Exception e) {
                logger.warn("onTurnFlushed 回调执行失败", e);
            }
        }
    }

    /**
     * 强制落盘（用于 close 等场景的兜底）。
     */
    public void forceFlush(String sessionId) {
        if (!currentTurn.isEmpty()) {
            flushTurn(sessionId);
        }
    }

    // ==================== 加载 ====================

    /**
     * 获取完整的会话上下文（磁盘 + 内存未落盘部分）。
     */
    public List<ContextMessage> getFullHistory(String sessionId) {
        List<ContextMessage> result = new ArrayList<>();
        if (sessionId != null) {
            Path sessionDir = sessionBaseDir.resolve(sessionId);
            if (Files.isDirectory(sessionDir)) {
                try {
                    Files.list(sessionDir)
                            .filter(p -> p.getFileName().toString().startsWith("turn_")
                                    && p.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .forEach(turnFile -> {
                                try {
                                    String content = new String(Files.readAllBytes(turnFile), StandardCharsets.UTF_8);
                                    JsonArray array = JsonParser.parseString(content).getAsJsonArray();
                                    for (JsonElement elem : array) {
                                        result.add(deserializeMessage(elem.getAsJsonObject()));
                                    }
                                } catch (IOException e) {
                                    logger.warn("读取 turn 文件失败: {}", turnFile, e);
                                }
                            });
                } catch (IOException e) {
                    logger.warn("遍历 session 目录失败: {}", sessionDir, e);
                }
            }
        }
        result.addAll(currentTurn);
        return Collections.unmodifiableList(result);
    }

    /**
     * 获取当前 turn 内存中尚未落盘的消息（只读）。
     */
    public List<ContextMessage> getCurrentTurn() {
        return Collections.unmodifiableList(currentTurn);
    }

    /**
     * 从磁盘加载已保存的文件路径列表。
     */
    public List<String> loadFilePaths(String sessionId) {
        if (sessionId == null) return Collections.emptyList();
        Path filesDir = sessionBaseDir.resolve(sessionId).resolve("files");
        if (!Files.isDirectory(filesDir)) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        try {
            Files.list(filesDir).forEach(p -> result.add(p.toAbsolutePath().toString()));
        } catch (IOException e) {
            logger.warn("读取文件目录失败: {}", filesDir, e);
        }
        return result;
    }

    /**
     * 恢复会话的内存状态（用于 session/load 后）。
     * <p>
     * 从磁盘恢复 fileAbsolutePaths 和 turnCounter，
     * 使 historyManager 的状态与已落盘的数据一致。
     */
    public void restoreState(String sessionId) {
        if (sessionId == null) return;

        // 恢复文件路径
        fileAbsolutePaths.addAll(loadFilePaths(sessionId));

        // 恢复 turn 计数器：统计已有的 turn 文件数
        Path sessionDir = sessionBaseDir.resolve(sessionId);
        if (Files.isDirectory(sessionDir)) {
            try {
                long turnCount = Files.list(sessionDir)
                        .filter(p -> p.getFileName().toString().startsWith("turn_")
                                && p.getFileName().toString().endsWith(".json"))
                        .count();
                turnCounter.set((int) turnCount);
            } catch (IOException e) {
                logger.warn("恢复 turnCounter 失败: {}", sessionDir, e);
            }
        }

        logger.info("会话状态已恢复, sessionId={}, files={}, turnCounter={}",
                sessionId, fileAbsolutePaths.size(), turnCounter.get());
    }


    /**
     * 重置状态（用于 session 重建等场景）。
     */
    public void reset() {
        currentTurn.clear();
        fileAbsolutePaths.clear();
        turnCounter.set(0);
    }

    /**
     * 查找当前 workspace 下最新的 sessionId。
     * <p>
     * 遍历 sessionBaseDir 下的所有子目录，按目录修改时间倒序排列，
     * 返回最新的目录名作为 sessionId。
     *
     * @return 最新的 sessionId，如果不存在则返回 null
     */
    
        /**
         * 查找当前 workspace 下最新的 sessionId。
         * <p>
         * 优先读取 last_session 标记文件（session/new 成功后写入），
         * 如果标记文件不存在，则回退到按目录修改时间查找。
         *
         * @return 最新的 sessionId，如果不存在则返回 null
         */
        public String findLatestSessionId() {
            // 优先读取标记文件
            Path marker = sessionBaseDir.resolve("last_session");
            if (Files.isRegularFile(marker)) {
                try {
                    String id = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
                    if (!id.isEmpty()) {
                        logger.info("从标记文件读取到最新 sessionId: {}", id);
                        return id;
                    }
                } catch (IOException e) {
                    logger.warn("读取 last_session 标记文件失败: {}", marker, e);
                }
            }

            // 回退：按目录修改时间查找
            if (!Files.isDirectory(sessionBaseDir)) {
                return null;
            }
            try {
                return Files.list(sessionBaseDir)
                        .filter(Files::isDirectory)
                        .filter(p -> {
                            try {
                                return Files.list(p).anyMatch(f ->
                                        f.getFileName().toString().startsWith("turn_")
                                                && f.getFileName().toString().endsWith(".json"));
                            } catch (IOException e) {
                                return false;
                            }
                        })
                        .max(Comparator.comparingLong(p -> {
                            try {
                                return Files.getLastModifiedTime(p).toMillis();
                            } catch (IOException e) {
                                return 0L;
                            }
                        }))
                        .map(p -> p.getFileName().toString())
                        .orElse(null);
            } catch (IOException e) {
                logger.warn("查找最新 sessionId 失败: {}", sessionBaseDir, e);
                return null;
            }
        }

        /**
         * 记录当前活跃的 sessionId 到标记文件。
         * 在 session/new 成功后立即调用，确保重启时能恢复到正确的会话。
         */
        public void saveLastSessionId(String sessionId) {
            if (sessionId == null) return;
            try {
                Files.createDirectories(sessionBaseDir);
                Path marker = sessionBaseDir.resolve("last_session");
                Files.write(marker, sessionId.getBytes(StandardCharsets.UTF_8));
                logger.info("已记录最新 sessionId: {}", sessionId);
            } catch (IOException e) {
                logger.warn("写入 last_session 标记文件失败, sessionId={}", sessionId, e);
            }
        }



    // ==================== 会话列表 ====================

    public static class SessionSummary {
        private final String sessionId;
        private final String preview;
        private final String lastModified;

        public SessionSummary(String sessionId, String preview, String lastModified) {
            this.sessionId = sessionId;
            this.preview = preview;
            this.lastModified = lastModified;
        }

        public String getSessionId() { return sessionId; }
        public String getPreview() { return preview; }
        public String getLastModified() { return lastModified; }
    }

    private static final Set<String> GREETINGS = new HashSet<>(Arrays.asList(
            "你好", "hi", "hello", "在吗", "hey", "嗨", "在不在", "您好"));

    /**
     * 列出最近的会话摘要。跳过纯寒暄，取第一条有实质内容的用户消息；
     * 若全是寒暄则取第一条 ASSISTANT 回复。截断到 30 字符。
     */
    public List<SessionSummary> listRecentSessions(int limit) {
        if (!Files.isDirectory(sessionBaseDir)) return Collections.emptyList();
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
            List<Path> dirs = Files.list(sessionBaseDir)
                    .filter(Files::isDirectory)
                    .filter(p -> {
                        try { return Files.list(p).anyMatch(f -> f.getFileName().toString().startsWith("turn_")); }
                        catch (IOException e) { return false; }
                    })
                    .sorted((a, b) -> {
                        try { return Long.compare(Files.getLastModifiedTime(b).toMillis(), Files.getLastModifiedTime(a).toMillis()); }
                        catch (IOException e) { return 0; }
                    })
                    .limit(limit)
                    .collect(java.util.stream.Collectors.toList());

            List<SessionSummary> result = new ArrayList<>();
            for (Path dir : dirs) {
                String sid = dir.getFileName().toString();
                String modified = sdf.format(new java.util.Date(Files.getLastModifiedTime(dir).toMillis()));
                String preview = extractPreview(dir);
                result.add(new SessionSummary(sid, preview, modified));
            }
            return result;
        } catch (IOException e) {
            logger.warn("listRecentSessions 失败", e);
            return Collections.emptyList();
        }
    }

    private String extractPreview(Path sessionDir) {
        try {
            List<Path> turnFiles = Files.list(sessionDir)
                    .filter(p -> p.getFileName().toString().startsWith("turn_") && p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());

            String firstAssistant = null;
            for (Path tf : turnFiles) {
                JsonArray arr = JsonParser.parseString(new String(Files.readAllBytes(tf), StandardCharsets.UTF_8)).getAsJsonArray();
                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    String role = obj.get("role").getAsString();
                    String content = obj.has("content") ? obj.get("content").getAsString() : null;
                    if (content == null || content.trim().isEmpty()) continue;
                    if ("USER".equals(role) && !GREETINGS.contains(content.trim().toLowerCase())) {
                        return sanitizePreview(content.trim(), 60);
                    }
                    if ("ASSISTANT".equals(role) && firstAssistant == null) {
                        firstAssistant = content.trim();
                    }
                }
            }
            return firstAssistant != null ? sanitizePreview(firstAssistant, 80) : "(空会话)";
        } catch (Exception e) {
            return "(读取失败)";
        }
    }

    /**
     * 对 preview 文本进行清洗：
     * 1. 换行符替换为分号
     * 2. 转义 HTML 标签和 Markdown 特殊字符
     * 3. 截取至指定长度
     */
    private static String sanitizePreview(String s, int max) {
        // 换行符替换为分号
        String result = s.replaceAll("[\\r\\n]+", "; ");
        // 转义 HTML 标签
        result = result.replace("&", "&amp;")
                       .replace("<", "&lt;")
                       .replace(">", "&gt;")
                       .replace("\"", "&quot;");
        // 转义 Markdown 特殊字符: * _ ` # ~ [ ] |
        result = result.replaceAll("([*_`#~\\[\\]|])", "\\\\$1");
        return truncate(result, max);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ==================== 内部方法 ====================

    private JsonObject serializeMessage(ContextMessage msg) {
        JsonObject obj = new JsonObject();
        obj.addProperty("role", msg.getRole().name());
        if (msg.getRole() == ContextMessage.Role.TOOL) {
            obj.addProperty("toolCallId", msg.getToolCallId());
            obj.addProperty("toolName", msg.getToolName());
            obj.addProperty("status", msg.getStatus());
            if (msg.getRawInput() != null) obj.add("rawInput", msg.getRawInput());
            if (msg.getRawOutput() != null) obj.add("rawOutput", msg.getRawOutput());
        } else {
            obj.addProperty("content", msg.getContent());
        }
        return obj;
    }

    private ContextMessage deserializeMessage(JsonObject obj) {
        ContextMessage.Role role = ContextMessage.Role.valueOf(obj.get("role").getAsString());
        if (role == ContextMessage.Role.TOOL) {
            return new ContextMessage(
                    obj.has("toolCallId") ? obj.get("toolCallId").getAsString() : null,
                    obj.has("toolName") ? obj.get("toolName").getAsString() : null,
                    obj.has("status") ? obj.get("status").getAsString() : null,
                    obj.has("rawInput") ? obj.getAsJsonObject("rawInput") : null,
                    obj.has("rawOutput") ? obj.getAsJsonObject("rawOutput") : null
            );
        }
        return new ContextMessage(role, obj.has("content") ? obj.get("content").getAsString() : "");
    }
}
