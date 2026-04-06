package com.mola.cmd.proxy.app.acp.memory;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.acpclient.ContextMessage;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryAction;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryIndex;
import com.mola.cmd.proxy.app.acp.memory.prompt.MemoryPromptTemplate;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 记忆提取器，封装子 Client 的调用和结果解析。
 * <p>
 * 所有提取请求（增量/全量）统一提交到内部单线程队列串行执行，保证：
 * <ul>
 *   <li>同一时刻只有一个提取任务在跑，天然避免索引并发写入</li>
 *   <li>调用方全部异步，不阻塞主对话流</li>
 *   <li>shutdown 时队列会执行完已提交的任务，不会丢失 close 时的提取</li>
 * </ul>
 */
public class MemoryExtractor {

    private static final Logger logger = LoggerFactory.getLogger(MemoryExtractor.class);

    private final MemoryConfig config;
    private final String command;
    private final String[] args;
    private final MemoryFileStore fileStore;

    /** 单线程提取队列，串行执行所有提取任务 */
    private final ExecutorService extractQueue = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(16),  // 有界队列，防止堆积过多
            r -> {
                Thread t = new Thread(r, "memory-extract-queue");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy()  // 队列满时丢弃最旧的（增量提取可丢，全量兜底）
    );

    /** 上次提取时的历史消息数量，用于增量提取 */
    private final AtomicInteger lastExtractedSize = new AtomicInteger(0);

    public MemoryExtractor(MemoryConfig config, String command, String[] args, MemoryFileStore fileStore) {
        this.config = config;
        this.command = command;
        this.args = args;
        this.fileStore = fileStore;
    }

    /**
     * 异步提交增量提取任务。
     * 只分析上次提取之后的新对话，每 N 轮触发时使用。
     */
    public void submitExtract(String workspacePath, List<ContextMessage> history) {
        if (history == null || history.isEmpty()) return;
        // 快照 history，避免异步执行时原列表被修改
        List<ContextMessage> snapshot = new ArrayList<>(history);
        try {
            extractQueue.submit(() -> doIncrementalExtract(workspacePath, snapshot));
        } catch (RejectedExecutionException e) {
            logger.warn("提取队列已满或已关闭，跳过本次增量提取");
        }
    }

    /**
     * 异步提交全量提取任务。
     * session 结束时使用，确保不遗漏。
     */
    public void submitExtractFull(String workspacePath, List<ContextMessage> history) {
        if (history == null || history.isEmpty()) return;
        List<ContextMessage> snapshot = new ArrayList<>(history);
        try {
            extractQueue.submit(() -> doFullExtract(workspacePath, snapshot));
        } catch (RejectedExecutionException e) {
            logger.warn("提取队列已满或已关闭，跳过本次全量提取");
        }
    }

    private void doIncrementalExtract(String workspacePath, List<ContextMessage> history) {
        int lastSize = lastExtractedSize.get();
        if (lastSize >= history.size()) {
            logger.info("无新对话内容，跳过增量提取");
            return;
        }
        List<ContextMessage> toExtract = history.subList(lastSize, history.size());
        logger.info("增量提取, 新消息数={}, 总消息数={}", toExtract.size(), history.size());
        doExtract(workspacePath, toExtract);
        lastExtractedSize.set(history.size());
    }

    private void doFullExtract(String workspacePath, List<ContextMessage> history) {
        logger.info("全量提取, 消息数={}", history.size());
        doExtract(workspacePath, history);
        lastExtractedSize.set(history.size());
    }

    private void doExtract(String workspacePath, List<ContextMessage> history) {
        String historyText = serializeHistory(history);
        MemoryIndex existingIndex = fileStore.loadIndex(workspacePath);
        String prompt = MemoryPromptTemplate.build(historyText, existingIndex);

        String groupId = "memory_extractor__" + workspacePath.hashCode();

        try (MemoryAcpClient client = new MemoryAcpClient(
                command, args, workspacePath, groupId, config.getSubClientTimeout())) {
            client.start();
            String response = client.sendPromptSync(prompt);
            logger.info("记忆提取子 Client 返回, 长度={}", response.length());

            List<MemoryAction> actions = parseActions(response);
            if (actions.isEmpty()) {
                logger.info("无需保存的记忆");
                return;
            }

            fileStore.applyActions(workspacePath, actions, existingIndex, config.getMaxEntriesPerProject());
            logger.info("记忆提取完成, 操作数={}", actions.size());
        } catch (Exception e) {
            logger.error("记忆提取失败, workspacePath={}", workspacePath, e);
        }
    }

    // ==================== 对话历史序列化 ====================

    private String serializeHistory(List<ContextMessage> history) {
        StringBuilder sb = new StringBuilder();
        for (ContextMessage msg : history) {
            switch (msg.getRole()) {
                case USER:
                    sb.append("USER: ").append(msg.getContent()).append("\n\n");
                    break;
                case ASSISTANT:
                    sb.append("ASSISTANT: ").append(msg.getContent()).append("\n\n");
                    break;
                case TOOL:
                    sb.append(String.format("TOOL [%s]: %s → %s\n\n",
                            msg.getToolName(),
                            msg.getRawInput() != null ? msg.getRawInput().toString() : "",
                            msg.getRawOutput() != null ? truncate(msg.getRawOutput().toString(), 500) : ""));
                    break;
            }
        }
        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    // ==================== JSON 解析 ====================

    List<MemoryAction> parseActions(String response) {
        List<MemoryAction> actions = new ArrayList<>();
        if (response == null || response.trim().isEmpty()) {
            return actions;
        }

        String json = extractJsonArray(response);
        if (json == null) {
            logger.warn("无法从响应中提取 JSON 数组");
            return actions;
        }

        // 预处理：LLM 生成的 JSON 中 detail/summary 字段可能包含中文引号（\u201c \u201d），
        // Gson 即使 lenient 模式也会将其误判为 JSON 字符串边界导致解析失败。
        // 将中文引号替换为中文括号，保留语义且不影响 JSON 解析。
        json = json.replace('\u201c', '「')
                   .replace('\u201d', '」')
                   .replace('\u2018', '「')
                   .replace('\u2019', '」');

        try {
            // 使用 lenient 模式解析，容忍子 Client 返回的非严格 JSON
            // （如 detail 字段中的中文引号、未转义换行符等）
            com.google.gson.stream.JsonReader jsonReader = new com.google.gson.stream.JsonReader(new StringReader(json));
            jsonReader.setLenient(true);
            JsonArray array = JsonParser.parseReader(jsonReader).getAsJsonArray();
            for (JsonElement elem : array) {
                JsonObject obj = elem.getAsJsonObject();
                MemoryAction action = new MemoryAction();

                String actionStr = obj.has("action") ? obj.get("action").getAsString() : "NOOP";
                try {
                    action.setAction(MemoryAction.ActionType.valueOf(actionStr.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    action.setAction(MemoryAction.ActionType.NOOP);
                }

                if (obj.has("id")) action.setId(obj.get("id").getAsString());
                if (obj.has("type")) action.setType(obj.get("type").getAsString());
                if (obj.has("title")) action.setTitle(obj.get("title").getAsString());
                if (obj.has("summary")) action.setSummary(obj.get("summary").getAsString());
                if (obj.has("detail")) action.setDetail(obj.get("detail").getAsString());
                if (obj.has("tags") && obj.get("tags").isJsonArray()) {
                    List<String> tags = new ArrayList<>();
                    for (JsonElement tag : obj.getAsJsonArray("tags")) {
                        tags.add(tag.getAsString());
                    }
                    action.setTags(tags);
                }

                actions.add(action);
            }
        } catch (JsonSyntaxException e) {
            logger.error("解析记忆提取结果失败: {}", json, e);
        }
        return actions;
    }

    private String extractJsonArray(String text) {
        int codeStart = text.indexOf("```json");
        if (codeStart >= 0) {
            int jsonStart = text.indexOf('\n', codeStart);
            int codeEnd = text.indexOf("```", jsonStart);
            if (jsonStart >= 0 && codeEnd > jsonStart) {
                return text.substring(jsonStart, codeEnd).trim();
            }
        }
        codeStart = text.indexOf("```");
        if (codeStart >= 0) {
            int jsonStart = text.indexOf('\n', codeStart);
            int codeEnd = text.indexOf("```", jsonStart);
            if (jsonStart >= 0 && codeEnd > jsonStart) {
                String candidate = text.substring(jsonStart, codeEnd).trim();
                if (candidate.startsWith("[")) return candidate;
            }
        }
        int bracketStart = text.indexOf('[');
        int bracketEnd = text.lastIndexOf(']');
        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            return text.substring(bracketStart, bracketEnd + 1);
        }
        return null;
    }

    /**
     * 优雅关闭：不再接受新任务，等待已提交的任务执行完毕。
     * 最多等待 subClientTimeout * 2 秒（给队列中可能的两个任务足够时间）。
     */
    public void shutdown() {
        extractQueue.shutdown();
        try {
            if (!extractQueue.awaitTermination(config.getSubClientTimeout() * 2L, TimeUnit.SECONDS)) {
                logger.warn("提取队列关闭超时，强制终止");
                extractQueue.shutdownNow();
            }
        } catch (InterruptedException e) {
            extractQueue.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
