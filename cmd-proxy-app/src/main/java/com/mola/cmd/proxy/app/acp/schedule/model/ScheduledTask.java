package com.mola.cmd.proxy.app.acp.schedule.model;

import com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelDeliveryContext;

/**
 * 定时任务数据模型。
 * <p>
 * 持久化到 ~/.cmd-proxy/schedules/{robotName}/tasks.json
 */
public class ScheduledTask {

    /** 任务 ID，由 ScheduleTaskManager 生成，格式: t{时间戳秒数}_{title-slug} */
    private String id;

    /** MAIN/TEAM 显式 owner；旧版任务读取时可为空并由目录迁移补齐。 */
    private ScheduleOwnerKey owner;

    /** 任务标题 */
    private String title;

    /** 执行内容（发给 Agent 的 prompt） */
    private String prompt;

    /** 可选的定时会话分组；同 owner 下同名任务复用同一 ACP session。 */
    private String groupName;

    /** 调度配置 */
    private ScheduleConfig schedule;

    /** 任务状态：WAITING / RUNNING */
    private String status;

    /** 创建时间（毫秒时间戳） */
    private long createdAt;

    /** 上次执行时间（毫秒时间戳），未执行过为 null */
    private Long lastRunAt;

    /** 下次执行时间（毫秒时间戳） */
    private long nextRunAt;

    /** Optional channel-neutral creator identity inherited by scheduled executions. */
    private AuthPrincipalContext authPrincipalContext;

    /** Durable origin conversation used to deliver scheduled output precisely. */
    private ChannelDeliveryContext channelDeliveryContext;

    public ScheduledTask() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public ScheduleOwnerKey getOwner() {
        return owner;
    }

    public void setOwner(ScheduleOwnerKey owner) {
        this.owner = owner;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public ScheduleConfig getSchedule() {
        return schedule;
    }

    public void setSchedule(ScheduleConfig schedule) {
        this.schedule = schedule;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(Long lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public long getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(long nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public AuthPrincipalContext getAuthPrincipalContext() { return authPrincipalContext; }
    public void setAuthPrincipalContext(AuthPrincipalContext context) {
        this.authPrincipalContext = context;
    }
    public ChannelDeliveryContext getChannelDeliveryContext() { return channelDeliveryContext; }
    public void setChannelDeliveryContext(ChannelDeliveryContext context) {
        this.channelDeliveryContext = context;
    }

    // ==================== 常量 ====================

    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_RUNNING = "RUNNING";
}
