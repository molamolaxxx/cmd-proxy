package com.mola.cmd.proxy.app.acp.schedule.model;

/**
 * 定时任务调度配置。
 * <p>
 * 支持两种类型：
 * <ul>
 *   <li>{@code cron} - 标准 cron 表达式，周期性任务</li>
 *   <li>{@code once} - 一次性任务，expr 为 ISO 时间戳或相对时间（如 +30m）</li>
 * </ul>
 */
public class ScheduleConfig {

    /** 调度类型：cron 或 once */
    private String type;

    /** 调度表达式：cron 表达式 或 ISO 时间戳 / 相对时间 */
    private String expr;

    public ScheduleConfig() {
    }

    public ScheduleConfig(String type, String expr) {
        this.type = type;
        this.expr = expr;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getExpr() {
        return expr;
    }

    public void setExpr(String expr) {
        this.expr = expr;
    }

    public boolean isCron() {
        return "cron".equalsIgnoreCase(type);
    }

    public boolean isOnce() {
        return "once".equalsIgnoreCase(type);
    }
}
