package com.mola.cmd.proxy.app.acp.acpclient;

/**
 * sendPrompt 的扩展参数。
 * <p>
 * 用于在不修改 sendPrompt 方法签名的前提下，传递额外的上下文控制参数。
 * 遵循无状态原则：每次调用独立构造，不依赖全局变量。
 */
public class PromptOptions {

    /** 是否为定时任务触发的执行场景（防止套娃：不注入定时任务上下文） */
    private boolean scheduleExecution;

    public PromptOptions() {
    }

    public boolean isScheduleExecution() {
        return scheduleExecution;
    }

    public PromptOptions setScheduleExecution(boolean scheduleExecution) {
        this.scheduleExecution = scheduleExecution;
        return this;
    }

    /** 默认选项（普通用户对话） */
    public static PromptOptions defaults() {
        return new PromptOptions();
    }

    /** 定时任务执行场景的选项 */
    public static PromptOptions forScheduleExecution() {
        return new PromptOptions().setScheduleExecution(true);
    }
}
