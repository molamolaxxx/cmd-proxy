package com.mola.cmd.proxy.app.acp.schedule.model;

/** Read-only projection of one durable scheduled execution attempt. */
public final class ScheduleExecutionRecord {
    private final String id;
    private final String taskId;
    private final String title;
    private final String ownerScope;
    private final String ownerPath;
    private final String ownerId;
    private final String robotName;
    private final String teamId;
    private final String teamMemberId;
    private final String groupName;
    private final String scheduleType;
    private final String scheduleExpr;
    private final long scheduledAt;
    private final long startedAt;
    private final Long finishedAt;
    private final long delayMillis;
    private final int attempt;
    private final String status;
    private final String resultCode;
    private final String detail;

    public ScheduleExecutionRecord(String id, String taskId, String title,
                                   String ownerScope, String ownerPath,
                                   String ownerId, String robotName,
                                   String teamId, String teamMemberId,
                                   String groupName, String scheduleType,
                                   String scheduleExpr, long scheduledAt,
                                   long startedAt, Long finishedAt,
                                   long delayMillis, int attempt, String status,
                                   String resultCode, String detail) {
        this.id = id;
        this.taskId = taskId;
        this.title = title;
        this.ownerScope = ownerScope;
        this.ownerPath = ownerPath;
        this.ownerId = ownerId;
        this.robotName = robotName;
        this.teamId = teamId;
        this.teamMemberId = teamMemberId;
        this.groupName = groupName;
        this.scheduleType = scheduleType;
        this.scheduleExpr = scheduleExpr;
        this.scheduledAt = scheduledAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.delayMillis = delayMillis;
        this.attempt = attempt;
        this.status = status;
        this.resultCode = resultCode;
        this.detail = detail;
    }

    public String getId() { return id; }
    public String getTaskId() { return taskId; }
    public String getTitle() { return title; }
    public String getOwnerScope() { return ownerScope; }
    public String getOwnerPath() { return ownerPath; }
    public String getOwnerId() { return ownerId; }
    public String getRobotName() { return robotName; }
    public String getTeamId() { return teamId; }
    public String getTeamMemberId() { return teamMemberId; }
    public String getGroupName() { return groupName; }
    public String getScheduleType() { return scheduleType; }
    public String getScheduleExpr() { return scheduleExpr; }
    public long getScheduledAt() { return scheduledAt; }
    public long getStartedAt() { return startedAt; }
    public Long getFinishedAt() { return finishedAt; }
    public long getDelayMillis() { return delayMillis; }
    public int getAttempt() { return attempt; }
    public String getStatus() { return status; }
    public String getResultCode() { return resultCode; }
    public String getDetail() { return detail; }
}
