package com.mola.cmd.proxy.app.acp.memory;

import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryEntry;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 记忆加载器，负责从文件加载记忆索引并构建注入 prompt 的概要文本。
 */
public class MemoryLoader {

    private static final Logger logger = LoggerFactory.getLogger(MemoryLoader.class);

    private final MemoryFileStore fileStore;
    private final MemoryConfig config;

    public MemoryLoader(MemoryFileStore fileStore, MemoryConfig config) {
        this.fileStore = fileStore;
        this.config = config;
    }

    /**
     * 构建注入 prompt 的记忆概要文本。
     * 按 updatedAt 降序排列，超出 indexMaxLines 限制时截断。
     *
     * @param workspacePath 当前工作目录
     * @return 概要文本，无记忆时返回空字符串
     */
    public String buildMemoryPrompt(String workspacePath) {
        MemoryIndex index = fileStore.loadIndex(workspacePath);
        if (index == null || index.getMemories().isEmpty()) {
            return "";
        }

        List<MemoryEntry> sorted = index.getMemories().stream()
                .sorted(Comparator.comparing(
                        (MemoryEntry e) -> e.getUpdatedAt() != null ? e.getUpdatedAt() : "")
                        .reversed())
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("[记忆上下文]\n");
        sb.append("你有以下跨 session 的长期记忆。每条包含概要和明细文件的绝对路径。\n");
        sb.append("⚠️ 记忆使用规则：\n");
        sb.append("- 概要仅用于判断「哪些记忆与当前问题相关」，不包含完整信息\n");
        sb.append("- 一旦判定某条记忆与当前任务相关，必须读取其明细文件后再回答，严禁仅凭概要推测细节\n");
        sb.append("- 如果不确定是否相关，宁可多读一条也不要漏读\n");
        sb.append("⚠️ 记忆文件由记忆系统自动管理，你只能读取，严禁写入、修改或删除。\n");
        sb.append("⚠️ 当用户要求管理记忆（删除、修改、新增、忘记等）时，你无需自己操作，只需在回复中明确表达你理解了用户的意图即可。");
        sb.append("记忆管理子系统会在后续自动读取本次对话，识别并执行相应的记忆操作。\n\n");

        int lineCount = 4; // 头部已用行数
        int maxLines = config.getIndexMaxLines();
        int shown = 0;

        for (MemoryEntry entry : sorted) {
            if (lineCount + 3 > maxLines) break;
            shown++;
            String tagStr = (entry.getTags() != null && !entry.getTags().isEmpty())
                    ? " #" + String.join(" #", entry.getTags())
                    : "";
            sb.append(String.format("%d. [%s] %s：%s%s\n   \uD83D\uDCC4 %s\n\n",
                    shown, entry.getType(), entry.getTitle(),
                    entry.getSummary(), tagStr, entry.getFile()));
            lineCount += 3;
        }

        if (shown < sorted.size()) {
            sb.append(String.format("... 还有 %d 条较早的记忆未列出。如需查看完整列表，请读取索引文件：\n\uD83D\uDCC4 %s\n",
                    sorted.size() - shown,
                    fileStore.getIndexPath(workspacePath)));
        }

        return sb.toString();
    }
    /**
     * 构建纯记忆条目概要，不含主 agent 专用的警告和操作提示。
     * 供能力反思等子系统使用。
     *
     * @param workspacePath 当前工作目录
     * @return 概要文本，无记忆时返回空字符串
     */
    public String buildMemorySummary(String workspacePath) {
        MemoryIndex index = fileStore.loadIndex(workspacePath);
        if (index == null || index.getMemories().isEmpty()) {
            return "";
        }

        List<MemoryEntry> sorted = index.getMemories().stream()
                .sorted(Comparator.comparing(
                        (MemoryEntry e) -> e.getUpdatedAt() != null ? e.getUpdatedAt() : "")
                        .reversed())
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (MemoryEntry entry : sorted) {
            if (shown >= config.getIndexMaxLines() / 3) break;
            shown++;
            String tagStr = (entry.getTags() != null && !entry.getTags().isEmpty())
                    ? " #" + String.join(" #", entry.getTags())
                    : "";
            sb.append(String.format("%d. [%s] %s：%s%s\n",
                    shown, entry.getType(), entry.getTitle(),
                    entry.getSummary(), tagStr));
        }

        if (shown < sorted.size()) {
            sb.append(String.format("... 还有 %d 条较早的记忆未列出。\n", sorted.size() - shown));
        }

        return sb.toString();
    }
}
