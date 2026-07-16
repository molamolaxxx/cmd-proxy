package com.mola.cmd.proxy.app.acp.memory.model;

/**
 * 记忆系统配置，对应 robot 级别的 "memory" 字段。
 * 每个 robot 可独立配置。readEnabled 控制读取召回，writeEnabled 控制提取写入，互不制约。
 */
public class MemoryConfig {

    private boolean readEnabled = true;
    private boolean writeEnabled = true;
    private String baseDir = System.getProperty("user.home") + "/.cmd-proxy/memory";
    private int extractIntervalTurns = 5;
    private int indexMaxLines = 200;
    private int maxEntriesPerProject = 30;

    private int projectExpireDays = 30;
    private int subClientTimeout = 120;

    /** 记忆隔离级别："workspace"（默认，同工作区共享）或 "robot"（按 robot name 隔离） */
    private String scope = "workspace";

    // Dream（记忆整理）相关配置
    private boolean dreamEnabled = true;
    private int dreamMinHours = 24;
    private int dreamMinSessions = 5;

    public boolean isReadEnabled() { return readEnabled; }
    public void setReadEnabled(boolean readEnabled) { this.readEnabled = readEnabled; }

    public boolean isWriteEnabled() { return writeEnabled; }
    public void setWriteEnabled(boolean writeEnabled) { this.writeEnabled = writeEnabled; }

    /** 读或写任一开启则视为记忆系统启用 */
    public boolean isEnabled() { return readEnabled || writeEnabled; }

    public String getBaseDir() { return baseDir; }
    public void setBaseDir(String baseDir) { this.baseDir = baseDir; }

    public int getExtractIntervalTurns() { return extractIntervalTurns; }
    public void setExtractIntervalTurns(int extractIntervalTurns) { this.extractIntervalTurns = extractIntervalTurns; }

    public int getIndexMaxLines() { return indexMaxLines; }
    public void setIndexMaxLines(int indexMaxLines) { this.indexMaxLines = indexMaxLines; }

    public int getMaxEntriesPerProject() { return maxEntriesPerProject; }
    public void setMaxEntriesPerProject(int maxEntriesPerProject) { this.maxEntriesPerProject = maxEntriesPerProject; }



    public int getProjectExpireDays() { return projectExpireDays; }
    public void setProjectExpireDays(int projectExpireDays) { this.projectExpireDays = projectExpireDays; }

    public int getSubClientTimeout() { return subClientTimeout; }
    public void setSubClientTimeout(int subClientTimeout) { this.subClientTimeout = subClientTimeout; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public boolean isRobotScope() { return "robot".equals(scope); }

    public boolean isDreamEnabled() { return dreamEnabled; }
    public void setDreamEnabled(boolean dreamEnabled) { this.dreamEnabled = dreamEnabled; }

    public int getDreamMinHours() { return dreamMinHours; }
    public void setDreamMinHours(int dreamMinHours) { this.dreamMinHours = dreamMinHours; }

    public int getDreamMinSessions() { return dreamMinSessions; }
    public void setDreamMinSessions(int dreamMinSessions) { this.dreamMinSessions = dreamMinSessions; }

}
