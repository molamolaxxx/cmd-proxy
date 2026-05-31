package com.mola.cmd.proxy.app.acp.common;

/**
 * 「直接输出 JSON」指令格式的共享提示词工具。
 * <p>
 * 项目中有多个通过直接在回复中输出 JSON 来触发系统行为的机制：
 * <ul>
 *   <li>{@code dispatch_subagent} — 子 Agent 派发</li>
 *   <li>{@code talk_to} — 跨 Agent 异步通讯</li>
 *   <li>{@code schedule_task / manage_schedule} — 定时任务管理</li>
 * </ul>
 * <p>
 * 这些机制要求 LLM 直接在回复文本中输出 JSON，而不是通过
 * Bash echo / Write 等工具来发送。本工具类提供统一的提示词模板，
 * 包含正反对比示例，降低 LLM 误用工具的概率。
 * <p>
 * 使用方式：
 * <pre>{@code
 *   StringBuilder sb = new StringBuilder();
 *   sb.append("{\"action\":\"talk_to\",\"target\":\"...\",\"content\":\"...\"}\n");
 *   DirectJsonOutputHelper.appendUsageWarning(sb,
 *       "发送 talk_to 消息",
 *       "拦截该 JSON 并将其路由到目标 Agent");
 * }</pre>
 */
public final class DirectJsonOutputHelper {

    private DirectJsonOutputHelper() {
        // 工具类，禁止实例化
    }

    /**
     * 追加统一的「直接输出 JSON」使用说明。
     * <p>
     * 调用方应在展示 JSON 格式示例之后调用此方法。
     *
     * @param sb                目标 StringBuilder
     * @param actionDescription 动作描述，如 "发送 talk_to 消息"、"派发子 Agent 任务"
     * @param resultDescription 系统处理描述，如 "拦截该 JSON 并将其路由到目标 Agent"、
     *                          "解析 tasks 并创建子 Agent 实例"
     */
    public static void appendUsageWarning(StringBuilder sb,
                                          String actionDescription,
                                          String resultDescription) {
        sb.append("\n");
        sb.append("⚠️ 如何").append(actionDescription).append("（重要，请仔细阅读）：\n");
        sb.append("请在回复中直接输出上述 JSON 文本（独占一行）。\n");
        sb.append("注意：是直接作为回复文本输出，不要通过 Bash echo、Write 等任何工具来发送，也不要包裹在 ``` 代码块中。\n");
        sb.append("\n");
        sb.append("❌ 错误做法：用 Bash 执行 echo '{\"action\":\"...\",...}' —— 系统不会识别\n");
        sb.append("✅ 正确做法：直接在回复中输出原始 JSON 文本\n");
        sb.append("\n");
        sb.append("输出 JSON 后必须立即结束当前回复，不要在 JSON 之后继续输出任何文字。\n");
        sb.append("系统会自动").append(resultDescription).append("。\n");
    }
}
