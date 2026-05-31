package com.mola.cmd.proxy.app.acp.schedule;

import com.mola.cmd.proxy.app.acp.common.DirectJsonOutputHelper;
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
 *   <li>当前对话是定时任务触发的执行场景（防止套娃）</li>
 * </ul>
 */
public class ScheduleContextInjector {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleContextInjector.class);

    /**
     * 构建定时任务能力描述文本，注入到主 Agent prompt 中。
     *
     * @param scheduleEnabled     该 robot 是否启用定时任务
     * @param isScheduleExecution 当前是否为定时任务触发的执行场景
     * @return 格式化的能力描述文本，不注入时返回 ""
     */
    public String buildContext(boolean scheduleEnabled, boolean isScheduleExecution) {
        if (!scheduleEnabled) {
            return "";
        }
        if (isScheduleExecution) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n[定时任务]\n");
        sb.append("你可以为用户设置和管理定时任务。\n\n");

        sb.append("设置任务（在回复中输出以下 JSON，独占一行，不要包裹在代码块中）：\n");
        sb.append("{\"action\":\"schedule_task\",\"tasks\":[{\"title\":\"任务标题\",\"prompt\":\"执行内容\",\"schedule\":{\"type\":\"cron|once\",\"expr\":\"cron表达式或ISO时间戳或相对时间如+30m\"}}]}\n\n");

        sb.append("查询任务列表：\n");
        sb.append("{\"action\":\"manage_schedule\",\"operation\":\"list\"}\n\n");

        sb.append("按ID操作任务（取消或更新）：\n");
        sb.append("{\"action\":\"manage_schedule\",\"operation\":\"cancel\",\"taskId\":\"任务ID\"}\n");
        sb.append("{\"action\":\"manage_schedule\",\"operation\":\"update\",\"taskId\":\"任务ID\",\"updates\":{\"title\":\"...\",\"prompt\":\"...\",\"schedule\":{\"type\":\"...\",\"expr\":\"...\"}}}\n\n");

        sb.append("schedule.type 支持：cron（标准5位cron表达式，周期性）、once（一次性，expr为ISO时间戳如2026-05-06T09:00:00，或相对时间如+30s/+30m/+2h/+1d）\n");
        sb.append("tasks 数组可包含多个任务，一次创建多个定时任务。\n\n");
        DirectJsonOutputHelper.appendUsageWarning(sb,
                "管理定时任务",
                "解析指令并执行对应的定时任务操作");

        return sb.toString();
    }
}
