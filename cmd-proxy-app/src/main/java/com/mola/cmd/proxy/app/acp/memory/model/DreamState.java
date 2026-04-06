package com.mola.cmd.proxy.app.acp.memory.model;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * 记忆整理状态模型，对应 DREAM_STATE.json。
 * 用于追踪整理触发条件和上次整理结果。
 */
public class DreamState {

    private String lastDreamTime;
    private int sessionsSinceLastDream;

    // 上次整理结果（用于日志和诊断）
    private int lastMerged;
    private int lastRemoved;
    private int lastDateFixed;
    private long lastDurationMs;

    /**
     * 计算距上次整理的小时数。
     * 从未整理过时返回 Integer.MAX_VALUE，确保首次一定满足时间条件。
     */
    public int getHoursSinceLastDream() {
        if (lastDreamTime == null) return Integer.MAX_VALUE;
        try {
            ZonedDateTime last = ZonedDateTime.parse(lastDreamTime);
            return (int) Duration.between(last, ZonedDateTime.now()).toHours();
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    public String getLastDreamTime() { return lastDreamTime; }
    public void setLastDreamTime(String lastDreamTime) { this.lastDreamTime = lastDreamTime; }

    public int getSessionsSinceLastDream() { return sessionsSinceLastDream; }
    public void setSessionsSinceLastDream(int sessionsSinceLastDream) { this.sessionsSinceLastDream = sessionsSinceLastDream; }

    public int getLastMerged() { return lastMerged; }
    public void setLastMerged(int lastMerged) { this.lastMerged = lastMerged; }

    public int getLastRemoved() { return lastRemoved; }
    public void setLastRemoved(int lastRemoved) { this.lastRemoved = lastRemoved; }

    public int getLastDateFixed() { return lastDateFixed; }
    public void setLastDateFixed(int lastDateFixed) { this.lastDateFixed = lastDateFixed; }

    public long getLastDurationMs() { return lastDurationMs; }
    public void setLastDurationMs(long lastDurationMs) { this.lastDurationMs = lastDurationMs; }
}
