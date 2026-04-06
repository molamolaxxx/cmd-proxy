package com.mola.cmd.proxy.app.acp.acpclient.context;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话上下文管理器，负责内存中的消息收集、磁盘落盘与加载。
 * <p>
 * 存储目录结构：
 * <pre>
 * ~/.cmd-proxy/session/{sessionId}/
 *   ├── turn_0000.json
 *   ├── turn_0001.json
 *   └── resources.json   (去重的图片 base64)
 * </pre>
 */
public class ConversationHistoryManager {

    private static final Logger logger = LoggerFactory.getLogger(ConversationHistoryManager.class);
    private static final Path SESSION_BASE_DIR = Paths.get(System.getProperty("user.home"), ".cmd-proxy", "session");
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 当前 turn 的上下文消息，flushTurn 后清空 */
    private final List<ContextMessage> currentTurn = new ArrayList<>();

    /** 累积的图片 base64（去重） */
    private final LinkedHashSet<String> imageBase64History = new LinkedHashSet<>();

    /** turn 计数器 */
    private final AtomicInteger turnCounter = new AtomicInteger(0);

    /** turn 完成回调，用于每 N 轮触发记忆提取等外部逻辑 */
    private Runnable onTurnFlushed;

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

    /** 追加图片 base64（自动去重） */
    public void addImages(Collection<String> images) {
        if (images == null) return;
        for (String img : images) {
            if (img != null && !img.isEmpty()) {
                imageBase64History.add(img);
            }
        }
    }

    // ==================== 落盘 ====================

    /**
     * 将当前 turn 的消息落盘并清空内存，同时同步写入图片资源文件。
     *
     * @param sessionId 当前会话 ID
     */
    public void flushTurn(String sessionId) {
        if (currentTurn.isEmpty() || sessionId == null) {
            return;
        }
        try {
            Path sessionDir = SESSION_BASE_DIR.resolve(sessionId);
            Files.createDirectories(sessionDir);

            int turn = turnCounter.getAndIncrement();
            Path turnFile = sessionDir.resolve(String.format("turn_%04d.json", turn));

            JsonArray array = new JsonArray();
            for (ContextMessage msg : currentTurn) {
                array.add(serializeMessage(msg));
            }

            Files.write(turnFile, PRETTY_GSON.toJson(array).getBytes(StandardCharsets.UTF_8));
            logger.info("会话上下文已落盘: {}, 消息数={}", turnFile, currentTurn.size());

            // 同步落盘图片资源
            flushImageResources(sessionDir);
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
            Path sessionDir = SESSION_BASE_DIR.resolve(sessionId);
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
     * 从磁盘加载图片资源。
     */
    public List<String> loadImageResources(String sessionId) {
        if (sessionId == null) return Collections.emptyList();
        Path resourceFile = SESSION_BASE_DIR.resolve(sessionId).resolve("resources.json");
        if (!Files.exists(resourceFile)) return Collections.emptyList();
        try {
            String content = new String(Files.readAllBytes(resourceFile), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            JsonArray images = root.getAsJsonArray("images");
            List<String> result = new ArrayList<>();
            if (images != null) {
                for (JsonElement elem : images) {
                    result.add(elem.getAsString());
                }
            }
            return result;
        } catch (IOException e) {
            logger.warn("读取图片资源失败: {}", resourceFile, e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取内存中的图片 base64 集合（只读）。
     */
    public Set<String> getImageBase64History() {
        return Collections.unmodifiableSet(imageBase64History);
    }

    /**
     * 重置状态（用于 session 重建等场景）。
     */
    public void reset() {
        currentTurn.clear();
        imageBase64History.clear();
        turnCounter.set(0);
    }

    // ==================== 内部方法 ====================

    private void flushImageResources(Path sessionDir) {
        if (imageBase64History.isEmpty()) return;
        try {
            Path resourceFile = sessionDir.resolve("resources.json");
            JsonArray images = new JsonArray();
            imageBase64History.forEach(images::add);

            JsonObject root = new JsonObject();
            root.add("images", images);

            Files.write(resourceFile, PRETTY_GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
            logger.info("图片资源已落盘: {}, 图片数={}", resourceFile, imageBase64History.size());
        } catch (IOException e) {
            logger.error("图片资源落盘失败: {}", sessionDir, e);
        }
    }

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
