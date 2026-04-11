package com.mola.cmd.proxy.app.acp.ability;

import java.util.List;

/**
 * 能力反思 prompt 模板。
 * <p>
 * 让 LLM 结合 skills（由 kiro-cli 自动注入上下文）、记忆概要和工具列表，
 * 反思自身具备的能力，输出结构化的能力清单。
 * <p>
 * 能力描述优先级：skills > 记忆 > 工具
 */
public class AbilityReflectionPromptTemplate {

    private AbilityReflectionPromptTemplate() {}

    /**
     * 构建能力反思 prompt。
     *
     * @param mcpServerNames MCP 工具服务名称列表
     * @param memorySummary  记忆概要文本（由 MemoryLoader 构建），可为空
     * @return 完整 prompt 文本
     */
    public static String build(List<String> mcpServerNames, String memorySummary) {
        StringBuilder sb = new StringBuilder();

        sb.append("你是能力反思子系统（Ability Reflection）。\n");
        sb.append("你的职责是综合分析自身的技能定义、历史记忆和可用工具，总结出一份清晰的能力清单。\n\n");

        sb.append("## 能力描述优先级\n");
        sb.append("在分析和总结能力时，请严格按照以下优先级排列：\n");
        sb.append("1. Skills（技能定义）— 最高优先级，这是你的核心能力定义，");
        sb.append("你的上下文中已自动加载了 skills 信息，请以此为主要依据\n");
        sb.append("2. 记忆（历史经验）— 中等优先级，从历史交互中积累的领域知识和经验\n");
        sb.append("3. 工具（MCP Servers）— 最低优先级，外部工具扩展的能力\n\n");
        // 记忆部分
        sb.append("## 历史记忆\n");
        if (memorySummary != null && !memorySummary.isEmpty()) {
            sb.append(memorySummary);
        } else {
            sb.append("（暂无历史记忆）\n");
        }
        sb.append("\n");

        // 工具部分
        sb.append("## 可用工具（MCP Servers）\n");
        if (mcpServerNames == null || mcpServerNames.isEmpty()) {
            sb.append("（无已配置的外部工具）\n");
        } else {
            for (String name : mcpServerNames) {
                sb.append("- ").append(name).append("\n");
            }
        }
        sb.append("\n");

        // 输出要求
        sb.append("## 输出要求\n");
        sb.append("请输出一份 Markdown 格式的能力总结文档，包含以下部分：\n");
        sb.append("1. **核心能力**：基于 skills 定义，你最擅长做什么\n");
        sb.append("2. **领域知识**：基于记忆积累，你在哪些领域有实际经验\n");
        sb.append("3. **工具链**：你可以调用哪些外部工具，各自的用途\n");
        sb.append("4. **局限性**：你做不到什么，需要注意什么\n\n");
        sb.append("直接输出 Markdown 内容，不要包裹在代码块中。\n");
        sb.append("内容应简洁实用，避免空泛描述，侧重于具体的、可操作的能力点。\n");

        return sb.toString();
    }
}
