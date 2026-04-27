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
     * @param description    robot 配置中的描述文本，可为空
     * @param mcpServerNames MCP 工具服务名称列表
     * @param memorySummary  记忆概要文本（由 MemoryLoader 构建），可为空
     * @return 完整 prompt 文本
     */
    public static String build(String description, List<String> mcpServerNames, String memorySummary) {
        StringBuilder sb = new StringBuilder();

        sb.append("你是能力反思子系统（Ability Reflection）。\n");
        sb.append("你的职责是综合分析自身的技能定义、历史记忆和可用工具，总结出一份清晰的能力清单。\n\n");

        sb.append("## 能力描述优先级\n");
        sb.append("在分析和总结能力时，请严格按照以下优先级排列：\n");
        sb.append("1. Robot 描述 — 最高优先级，这是用户对该 robot 定位和职责的直接定义\n");
        sb.append("2. Skills（技能定义）— 高优先级，这是你的核心能力定义，");
        sb.append("你的上下文中已自动加载了 skills 信息，请以此为主要依据\n");
        sb.append("3. 记忆（历史经验）— 中等优先级，从历史交互中积累的领域知识和经验\n");
        sb.append("4. 工具（MCP Servers）— 最低优先级，外部工具扩展的能力\n\n");

        // Robot 描述部分
        sb.append("## Robot 描述\n");
        if (description != null && !description.isEmpty()) {
            sb.append(description);
        } else {
            sb.append("（未配置 robot 描述）\n");
        }
        sb.append("\n\n");
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
        sb.append("请输出一份精简的 Markdown 能力卡片，总长度严格控制在 800 字以内。\n");
        sb.append("这份卡片的唯一用途是：让调度 Agent 判断「什么任务该派给你」。\n\n");
        sb.append("包含以下部分：\n");
        sb.append("1. **核心能力**（3~6 条）：只列出你独有的、区别于通用编码 Agent 的能力。");
        sb.append("每条一句话，突出「我能做什么别人做不了的」\n");
        sb.append("2. **领域知识**（关键词标签）：基于记忆积累，用逗号分隔的标签列表，最多 10 个。");
        sb.append("格式示例：`ACP协议, LevelDB调优, SVG动画`\n");
        sb.append("3. **专属工具**：只列外部 MCP Server 工具（如有），不要列内置工具");
        sb.append("（Bash/Read/Write/Glob/Grep 等所有 Agent 都有）\n");
        sb.append("4. **关键约束**（1~3 条）：只列该 Agent 特有的限制。");
        sb.append("不要列通用限制（如「无法启动长时间运行的服务」「无法进行 WCAG 验证」等所有 Agent 共有的约束）\n\n");
        sb.append("直接输出 Markdown 内容，不要包裹在代码块中。\n");
        sb.append("不要输出表格。不要输出空泛描述。不要重复通用能力。\n");

        return sb.toString();
    }
}
