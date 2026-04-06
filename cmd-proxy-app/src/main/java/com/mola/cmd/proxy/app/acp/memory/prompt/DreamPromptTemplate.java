package com.mola.cmd.proxy.app.acp.memory.prompt;

import com.mola.cmd.proxy.app.acp.memory.model.MemoryEntry;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryIndex;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 记忆整理 prompt 模板，构建发送给子 Client 的整理指令。
 */
public class DreamPromptTemplate {

    private DreamPromptTemplate() {}

    /**
     * 构建完整的记忆整理 prompt。
     *
     * @param index   当前记忆索引
     * @param details 所有明细文件内容 (key=memoryId, value=明细文本)
     * @return 完整 prompt 文本
     */
    public static String build(MemoryIndex index, Map<String, String> details) {
        StringBuilder sb = new StringBuilder();

        // 角色定义
        sb.append("你是记忆整理子系统（Memory Dream）。你的职责是审查和整合已有记忆，提升记忆质量。\n\n");

        // 当前日期
        sb.append("## 当前日期\n");
        sb.append(ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        sb.append("\n\n");

        // 全部记忆
        sb.append("## 全部记忆\n");
        sb.append("以下是当前项目的所有记忆条目，包含索引信息和明细内容：\n\n");

        int num = 0;
        for (MemoryEntry entry : index.getMemories()) {
            num++;
            sb.append("### 记忆 ").append(num).append("\n");
            sb.append("- id: ").append(entry.getId()).append("\n");
            sb.append("- type: ").append(entry.getType()).append("\n");
            sb.append("- title: ").append(entry.getTitle()).append("\n");
            sb.append("- summary: ").append(entry.getSummary()).append("\n");
            sb.append("- tags: [").append(String.join(", ", entry.getTags())).append("]\n");
            sb.append("- createdAt: ").append(entry.getCreatedAt()).append("\n");
            sb.append("- updatedAt: ").append(entry.getUpdatedAt()).append("\n");

            // 明细内容
            String detail = details.get(entry.getId());
            if (detail != null && !detail.isEmpty()) {
                sb.append("- detail:\n").append(detail).append("\n");
            }
            sb.append("\n");
        }

        // 整理规则
        appendRules(sb);

        // 输出格式
        appendOutputFormat(sb);

        return sb.toString();
    }

    private static void appendRules(StringBuilder sb) {
        sb.append("## 整理规则\n\n");
        sb.append("请执行以下整理操作：\n\n");

        sb.append("### 1. 矛盾消除\n");
        sb.append("识别语义上矛盾的记忆对。保留更新时间更晚的那条（通常反映最新状态），");
        sb.append("删除或更新过时的那条。\n");
        sb.append("示例：「API 用 Express」vs「已迁移到 Fastify」→ 删除前者。\n\n");

        sb.append("### 2. 重复合并\n");
        sb.append("识别语义高度重叠的记忆。合并为一条，保留最完整的信息，");
        sb.append("合并后的 detail 应包含所有来源的有价值内容。\n");
        sb.append("合并时使用被合并条目中 updatedAt 最新的那个作为参考。\n\n");

        sb.append("### 3. 过期清理\n");
        sb.append("基于当前日期判断：\n");
        sb.append("- 包含明确截止日期且已过期的记忆 → DELETE\n");
        sb.append("- project 类型记忆超过 30 天未更新 → DELETE\n");
        sb.append("- 引用了不太可能仍然存在的临时资源的记忆 → DELETE\n\n");

        sb.append("### 4. 日期规范化\n");
        sb.append("将 detail 中的相对日期表述转换为绝对日期。\n");
        sb.append("根据记忆的 createdAt/updatedAt 推算：\n");
        sb.append("- 「昨天」→ 基于 updatedAt 推算具体日期\n");
        sb.append("- 「上周」→ 推算为具体日期范围\n");
        sb.append("- 「最近」→ 替换为 updatedAt 对应的日期\n\n");

        sb.append("### 5. 碎片整合\n");
        sb.append("识别同一主题被拆分成多条的情况，合并为一条完整的记忆。\n");
        sb.append("判断标准：type 相同 + tags 高度重叠 + title/summary 语义相近。\n\n");
    }

    private static void appendOutputFormat(StringBuilder sb) {
        sb.append("## 输出格式\n\n");
        sb.append("请以 JSON 数组返回操作列表：\n");
        sb.append("```json\n");
        sb.append("[\n");
        sb.append("  {\n");
        sb.append("    \"action\": \"MERGE\",\n");
        sb.append("    \"sourceIds\": [\"memory_001\", \"memory_003\"],\n");
        sb.append("    \"result\": {\n");
        sb.append("      \"type\": \"feedback\",\n");
        sb.append("      \"title\": \"合并后的标题\",\n");
        sb.append("      \"summary\": \"合并后的一句话概要\",\n");
        sb.append("      \"detail\": \"合并后的完整内容\",\n");
        sb.append("      \"tags\": [\"tag1\", \"tag2\"]\n");
        sb.append("    }\n");
        sb.append("  },\n");
        sb.append("  {\n");
        sb.append("    \"action\": \"UPDATE\",\n");
        sb.append("    \"id\": \"memory_002\",\n");
        sb.append("    \"fields\": {\n");
        sb.append("      \"detail\": \"日期规范化后的 detail 内容\"\n");
        sb.append("    }\n");
        sb.append("  },\n");
        sb.append("  {\n");
        sb.append("    \"action\": \"DELETE\",\n");
        sb.append("    \"id\": \"memory_005\",\n");
        sb.append("    \"reason\": \"与 memory_002 矛盾，且更新时间更早\"\n");
        sb.append("  }\n");
        sb.append("]\n");
        sb.append("```\n\n");

        sb.append("规则：\n");
        sb.append("- MERGE：将 sourceIds 中的多条记忆合并为一条新记忆。sourceIds 中的旧条目会被删除。\n");
        sb.append("- UPDATE：更新指定记忆的部分字段（通常用于日期规范化）。fields 中只包含需要修改的字段。\n");
        sb.append("- DELETE：删除指定记忆。必须提供 reason。\n");
        sb.append("- NOOP：无需操作。可省略不写。\n");
        sb.append("- 只输出 JSON 数组，不要输出其他内容。\n");
        sb.append("- 如果所有记忆都无需整理，返回空数组 []。\n");
        sb.append("- JSON 字符串值中严禁使用中文引号（\u201c \u201d \u2018 \u2019），如需引用请用「」代替。\n");
    }
}
