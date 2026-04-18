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
        sb.append("你正在执行一次记忆整理（Memory Dream）——对已有记忆的反思性审查。\n");
        sb.append("你的目标是整合、去重、清理记忆，使未来的会话能快速获取准确的上下文。\n");
        sb.append("请像审慎的编辑一样工作：宁可保留一条可能有用的记忆，也不要误删有价值的信息。\n");
        sb.append("只有当你有充分理由时才执行操作，不确定时选择 NOOP。\n\n");

        // 当前日期
        sb.append("## 当前日期\n");
        sb.append(ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        sb.append("\n\n");

        // 全部记忆
        sb.append("## 全部记忆（共 ").append(index.getMemories().size()).append(" 条）\n");
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
        sb.append("请按以下优先级从高到低依次执行整理操作。高优先级规则的结果可能影响低优先级规则的判断，");
        sb.append("因此请严格按顺序评估。\n\n");

        sb.append("### 优先级 1：矛盾消除\n");
        sb.append("识别语义上矛盾的记忆对。保留更新时间更晚的那条（通常反映最新状态），");
        sb.append("删除或更新过时的那条。\n");
        sb.append("示例：「API 用 Express」vs「已迁移到 Fastify」→ 删除前者。\n\n");

        sb.append("### 优先级 2：重复合并\n");
        sb.append("识别语义高度重叠的记忆。合并为一条，保留最完整的信息，");
        sb.append("合并后的 detail 应包含所有来源的有价值内容。\n");
        sb.append("合并时使用被合并条目中 updatedAt 最新的那个作为参考。\n");
        sb.append("注意：先完成矛盾消除再做合并，避免将矛盾的记忆合并到一起。\n\n");

        sb.append("### 优先级 3：过期清理\n");
        sb.append("基于当前日期判断：\n");
        sb.append("- 包含明确截止日期且已过期的记忆 → DELETE\n");
        sb.append("- project 类型记忆超过 30 天未更新 → DELETE\n");
        sb.append("- 引用了不太可能仍然存在的临时资源的记忆 → DELETE\n\n");

        sb.append("### 优先级 4：已完成任务清理\n");
        sb.append("识别描述待办事项、TODO、临时 workaround 或一次性操作的记忆。\n");
        sb.append("如果从其他记忆的上下文中可以推断该任务已完成或该 workaround 已不再需要 → DELETE。\n");
        sb.append("示例：「TODO: 给 UserService 加日志」如果已有记忆提到「UserService 已添加结构化日志」→ 删除前者。\n\n");

        sb.append("### 优先级 5：被取代的中间过程清理\n");
        sb.append("识别描述探索过程、被否定的方案、调试中间步骤的记忆。\n");
        sb.append("如果已有更新的记忆记录了最终结论或采纳的方案，则中间试错过程可以删除。\n");
        sb.append("示例：「尝试用 Redis 做缓存，性能不达标」+「最终采用 Caffeine 本地缓存」→ 删除前者。\n\n");

        sb.append("### 优先级 6：低信息密度清理\n");
        sb.append("识别内容过于笼统、缺乏具体细节、对决策几乎无帮助的记忆 → DELETE。\n");
        sb.append("判断标准：detail 少于 20 字且 summary 不包含具体的技术细节、路径、命令或配置项。\n");
        sb.append("示例：「这个项目用了 Java」→ DELETE（信息密度过低，无实际指导价值）。\n\n");

        sb.append("### 优先级 7：日期规范化\n");
        sb.append("将 detail 中的相对日期表述转换为绝对日期。\n");
        sb.append("根据记忆的 createdAt/updatedAt 推算：\n");
        sb.append("- 「昨天」→ 基于 updatedAt 推算具体日期\n");
        sb.append("- 「上周」→ 推算为具体日期范围\n");
        sb.append("- 「最近」→ 替换为 updatedAt 对应的日期\n\n");

        sb.append("### 优先级 8：碎片整合\n");
        sb.append("识别同一主题被拆分成多条的情况，合并为一条完整的记忆。\n");
        sb.append("判断标准：type 相同 + tags 高度重叠 + title/summary 语义相近。\n\n");

        sb.append("### 安全护栏\n");
        sb.append("- 保守原则：如果不确定一条记忆是否应该被删除或修改，保留它（NOOP）。误保留的代价远低于误删除。\n");
        sb.append("- 单次整理操作总数不应超过记忆总数的 50%，避免一次性大规模清理导致信息丢失。\n");
        sb.append("- preference / style 类型的记忆（用户偏好、编码风格）应格外谨慎，除非明确矛盾否则不要删除。\n\n");
    }

    private static void appendOutputFormat(StringBuilder sb) {
        sb.append("## 输出格式\n\n");
        sb.append("请以 JSON 数组返回操作列表：\n");
        sb.append("```json\n");
        sb.append("[\n");
        sb.append("  {\n");
        sb.append("    \"action\": \"MERGE\",\n");
        sb.append("    \"sourceIds\": [\"memory_001\", \"memory_003\"],\n");
        sb.append("    \"reason\": \"两条记忆都描述了缓存策略，语义高度重叠\",\n");
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
        sb.append("    \"reason\": \"将相对日期转换为绝对日期\",\n");
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
        sb.append("- 每条操作都必须提供 reason 字段，简要说明执行该操作的依据，便于追溯和审计。\n");
        sb.append("- MERGE：将 sourceIds 中的多条记忆合并为一条新记忆。sourceIds 中的旧条目会被删除。\n");
        sb.append("- UPDATE：更新指定记忆的部分字段（通常用于日期规范化）。fields 中只包含需要修改的字段。\n");
        sb.append("- DELETE：删除指定记忆。\n");
        sb.append("- NOOP：无需操作。可省略不写。\n");
        sb.append("- 只输出 JSON 数组，不要输出其他内容。\n");
        sb.append("- 如果所有记忆都无需整理，返回空数组 []。\n");
        sb.append("- JSON 字符串值中严禁使用中文引号（\u201c \u201d \u2018 \u2019），如需引用请用「」代替。\n");
    }
}
