package com.mola.cmd.proxy.app.acp.memory.prompt;

import com.mola.cmd.proxy.app.acp.memory.model.MemoryEntry;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryIndex;

/**
 * 记忆提取 prompt 模板，构建发送给子 Client 的指令文本。
 */
public class MemoryPromptTemplate {

    private MemoryPromptTemplate() {}

    /**
     * 构建完整的记忆提取 prompt。
     *
     * @param historyText   序列化后的对话历史文本
     * @param existingIndex 已有记忆索引（用于去重和更新判断）
     * @return 完整 prompt 文本
     */
    public static String build(String historyText, MemoryIndex existingIndex) {
        int existingCount = (existingIndex != null) ? existingIndex.getMemories().size() : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("你是记忆管理子系统。你的职责是分析对话内容，提取值得跨 session 保留的记忆，并执行用户的记忆管理请求。\n\n");
        sb.append("你拥有对记忆的完整管理权限（ADD/UPDATE/DELETE），主 agent 没有这个权限。\n");
        sb.append("当对话中出现用户要求删除、修改、新增记忆的内容时（如「忘记xxx」「删除这条记忆」「记住xxx」等），\n");
        sb.append("你必须识别并执行这些请求，通过输出对应的 action 来完成操作。\n");
        sb.append("用户的显式记忆管理请求优先级最高——如果用户要求「忘记 X」，即使对话中再次提到 X 也不要重新 ADD。\n\n");

        sb.append("## 对话内容\n");
        sb.append(historyText).append("\n\n");

        sb.append("## 已有记忆索引（当前 ").append(existingCount).append(" 条，上限 50 条）\n");
        if (existingIndex != null && !existingIndex.getMemories().isEmpty()) {
            for (MemoryEntry entry : existingIndex.getMemories()) {
                sb.append(String.format("- [%s] %s (id=%s): %s\n",
                        entry.getType(), entry.getTitle(), entry.getId(), entry.getSummary()));
            }
        } else {
            sb.append("（暂无已有记忆）\n");
        }
        sb.append("\n");

        sb.append("## 去重与更新规则\n");
        sb.append("在决定 ADD 还是 UPDATE 之前，必须先检查已有记忆索引：\n");
        sb.append("- 如果新信息与某条已有记忆的主题高度重叠 → 使用 UPDATE（携带该条的 id），将新信息合并进去\n");
        sb.append("- 如果新信息与已有记忆矛盾 → 使用 UPDATE 覆盖旧内容，而非同时保留两条矛盾记忆\n");
        sb.append("- 只有当信息确实是全新主题时才使用 ADD\n");
        sb.append("- 当已有记忆接近上限（≥25 条）时，提高 ADD 门槛，只保存高价值信息\n\n");

        sb.append("## 提取规则\n");
        sb.append("请按以下分类提取记忆，只提取跨 session 有价值的信息：\n\n");
        sb.append("1. user（用户画像）：用户的角色、技术栈偏好、工作习惯、沟通风格偏好\n");
        sb.append("2. feedback（行为反馈）：用户对 agent 行为的纠正或肯定（如「不要自动加注释」「这种方式很好」）\n");
        sb.append("3. project（项目上下文）：项目目标、架构决策、技术约束等无法从代码库直接推导的信息\n");
        sb.append("4. reference（外部引用）：外部系统地址、文档链接、工具配置、常用命令等\n\n");

        sb.append("## 从工具调用中提取\n");
        sb.append("对话中的 TOOL 消息包含用户实际使用的工具和命令，注意从中识别：\n");
        sb.append("- 反复出现的构建/测试/部署命令模式 → 作为 reference 类型记忆\n");
        sb.append("- 工具调用揭示的项目配置或环境信息 → 作为 project 类型记忆\n");
        sb.append("- 但不要存储工具的原始输入输出内容本身\n\n");

        sb.append("## 不应存储的内容\n");
        sb.append("- 代码片段、具体文件路径、项目目录结构（可从代码库直接获取）\n");
        sb.append("- 可从代码直接推导的架构信息（如「项目用了 Spring Boot」如果 pom.xml 里能看到）\n");
        sb.append("- 仅在本次对话中有意义的临时调试步骤（如「试了重启服务，问题解决了」）\n");
        sb.append("- 已解决且不会复现的一次性问题\n");
        sb.append("- Git 历史可查的变更记录\n");
        sb.append("- 工具调用的原始输入输出文本\n\n");

        sb.append("## 输出格式\n");
        sb.append("请以 JSON 数组返回，每条记忆格式如下：\n");
        sb.append("```json\n");
        sb.append("[\n");
        sb.append("  {\n");
        sb.append("    \"action\": \"ADD\",\n");
        sb.append("    \"type\": \"feedback\",\n");
        sb.append("    \"title\": \"简短标题（10 字以内）\",\n");
        sb.append("    \"summary\": \"仅标注主题和类型的关键词式概要，不含具体结论或做法（20 字以内）\",\n");
        sb.append("    \"detail\": \"What: 具体内容，充分记录关键细节和涉及的组件，不要过度压缩。Why: 为什么重要。Apply: 未来如何应用。feedback/user 类型 150~400 字；project/reference 类型按需写够，不设上限，但避免冗余重复。\",\n");
        sb.append("    \"tags\": [\"标签1\", \"标签2\"]\n");
        sb.append("  },\n");
        sb.append("  {\n");
        sb.append("    \"action\": \"UPDATE\",\n");
        sb.append("    \"id\": \"memory_abc123\",\n");
        sb.append("    \"type\": \"project\",\n");
        sb.append("    \"title\": \"更新后的标题\",\n");
        sb.append("    \"summary\": \"更新后的概要\",\n");
        sb.append("    \"detail\": \"合并新旧信息后的完整内容\",\n");
        sb.append("    \"tags\": [\"标签1\"]\n");
        sb.append("  },\n");
        sb.append("  {\n");
        sb.append("    \"action\": \"DELETE\",\n");
        sb.append("    \"id\": \"memory_xyz789\"\n");
        sb.append("  }\n");
        sb.append("]\n");
        sb.append("```\n");
        sb.append("规则：\n");
        sb.append("- ADD：新增记忆。不需要 id 字段。\n");
        sb.append("- UPDATE：更新已有记忆。必须携带已有记忆的 id，并提供完整的更新后内容（非增量）。\n");
        sb.append("- DELETE：删除已有记忆。只需 id 字段。\n");
        sb.append("- NOOP：无需操作。可省略不写。\n");
        sb.append("- 如果没有值得保存的记忆，返回空数组 []。\n");
        sb.append("- 只输出 JSON 数组，不要输出其他内容。\n");
        sb.append("- JSON 字符串值中严禁使用中文引号（\u201c \u201d \u2018 \u2019），如需引用请用「」代替。\n");

        return sb.toString();
    }
}
