package com.mola.cmd.proxy.app.memory.prompt;

import com.mola.cmd.proxy.app.memory.model.MemoryEntry;
import com.mola.cmd.proxy.app.memory.model.MemoryIndex;

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
        StringBuilder sb = new StringBuilder();
        sb.append("你是记忆管理子系统。你的职责是分析对话内容，提取值得跨 session 保留的记忆，并执行用户的记忆管理请求。\n\n");
        sb.append("你拥有对记忆的完整管理权限（ADD/UPDATE/DELETE），主 agent 没有这个权限。\n");
        sb.append("当对话中出现用户要求删除、修改、新增记忆的内容时（如「忘记xxx」「删除这条记忆」「记住xxx」等），\n");
        sb.append("你必须识别并执行这些请求，通过输出对应的 action 来完成操作。\n\n");

        sb.append("## 对话内容\n");
        sb.append(historyText).append("\n\n");

        sb.append("## 已有记忆索引\n");
        if (existingIndex != null && !existingIndex.getMemories().isEmpty()) {
            for (MemoryEntry entry : existingIndex.getMemories()) {
                sb.append(String.format("- [%s] %s (id=%s): %s\n",
                        entry.getType(), entry.getTitle(), entry.getId(), entry.getSummary()));
            }
        } else {
            sb.append("（暂无已有记忆）\n");
        }
        sb.append("\n");

        sb.append("## 提取规则\n");
        sb.append("请按以下分类提取记忆，只提取跨 session 有价值的信息：\n\n");
        sb.append("1. **user（用户画像）**：用户的角色、技术栈偏好、工作习惯\n");
        sb.append("2. **feedback（行为反馈）**：用户对 agent 行为的纠正或肯定\n");
        sb.append("3. **project（项目上下文）**：项目目标、架构决策、技术约束等非代码可推导的信息\n");
        sb.append("4. **reference（外部引用）**：外部系统地址、文档链接、工具配置等\n\n");

        sb.append("## 不应存储的内容\n");
        sb.append("- 代码片段、文件路径、项目结构（可从代码库直接获取）\n");
        sb.append("- 临时性的调试过程和中间状态\n");
        sb.append("- 本次对话中已解决且不会复现的问题\n");
        sb.append("- Git 历史可查的变更记录\n\n");

        sb.append("## 输出格式\n");
        sb.append("请以 JSON 数组返回，每条记忆格式如下：\n");
        sb.append("```json\n");
        sb.append("[\n");
        sb.append("  {\n");
        sb.append("    \"action\": \"ADD\" | \"UPDATE\" | \"DELETE\" | \"NOOP\",\n");
        sb.append("    \"id\": \"memory_xxx\",\n");
        sb.append("    \"type\": \"user|feedback|project|reference\",\n");
        sb.append("    \"title\": \"简短标题\",\n");
        sb.append("    \"summary\": \"一句话概要（用于索引）\",\n");
        sb.append("    \"detail\": \"详细内容，包含 Why 和 How to apply\",\n");
        sb.append("    \"tags\": [\"标签1\", \"标签2\"]\n");
        sb.append("  }\n");
        sb.append("]\n");
        sb.append("```\n");
        sb.append("如果没有值得保存的记忆，返回空数组 []。\n");
        sb.append("重要：只输出 JSON 数组，不要输出其他内容。\n");
        sb.append("重要：JSON 字符串值中严禁使用中文引号（\u201c \u201d \u2018 \u2019），如需引用请用「」代替。\n");

        return sb.toString();
    }
}
