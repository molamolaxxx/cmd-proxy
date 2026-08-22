package com.mola.cmd.proxy.app.acp.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 定时任务上下文注入器。
 * <p>
 * 在主 Agent 的 sendPrompt() 中，向 prompt 注入定时任务的能力描述和操作格式，
 * 让 LLM 自主判断何时需要设置/管理定时任务。
 * <p>
 * 不注入的场景：
 * <ul>
 *   <li>robot 配置 scheduleEnabled = false</li>
 * </ul>
 */
public class ScheduleContextInjector {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleContextInjector.class);

    /**
     * 构建定时任务能力描述文本，注入到主 Agent prompt 中。
     *
     * @param scheduleEnabled     该 robot 是否启用定时任务
     * @param isScheduleExecution 当前是否为定时任务触发的执行场景；执行场景也需要
     *                            保留该能力上下文，便于后续人工接续会话
     * @return 格式化的能力描述文本，不注入时返回 ""
     */
    public String buildContext(boolean scheduleEnabled, boolean isScheduleExecution) {
        if (!scheduleEnabled) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n<scheduled-tasks>\n");
        sb.append("你可以为用户设置和管理定时任务。\n\n");

        sb.append("使用 schedule_task MCP 工具创建任务；使用 manage_schedule MCP 工具查询、取消或更新任务。\n");
        sb.append("直接调用工具，不要在回复正文中模拟调用或输出 Action JSON。\n\n");

        sb.append("schedule.type 支持：cron（标准5位cron表达式，周期性）、once（一次性，expr为ISO时间戳如2026-05-06T09:00:00，或相对时间如+30s/+30m/+2h/+1d）\n");
        sb.append("tasks 数组可包含多个任务，一次创建多个定时任务。\n\n");
        sb.append("groupName 可省略；指定后，同一 owner 下同名分组的定时任务会在同一会话中继续执行。\n\n");
        sb.append("</scheduled-tasks>\n");
        return sb.toString();
    }
}
