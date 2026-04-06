package com.mola.cmd.proxy.app.acp.memory.model;

/**
 * 记忆系统配置，对应 robot 级别的 "memory" 字段。
 * 每个 robot 可独立配置，未配置时 enabled=false。
 */
public class MemoryConfig {

    private boolean enabled = false;
    private String baseDir = System.getProperty("user.home") + "/.cmd-proxy/memory";
    private int extractIntervalTurns = 3;
    private int indexMaxLines = 200;
    private int maxEntriesPerProject = 50;
    private int maxEntriesGlobal = 20;
    private int projectExpireDays = 30;
    private int subClientTimeout = 30;
    private String agentProvider = "KIRO_CLI";

    // Dream（记忆整理）相关配置
    private boolean dreamEnabled = true;
    private int dreamMinHours = 24;
    private int dreamMinSessions = 5;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBaseDir() { return baseDir; }
    public void setBaseDir(String baseDir) { this.baseDir = baseDir; }

    public int getExtractIntervalTurns() { return extractIntervalTurns; }
    public void setExtractIntervalTurns(int extractIntervalTurns) { this.extractIntervalTurns = extractIntervalTurns; }

    public int getIndexMaxLines() { return indexMaxLines; }
    public void setIndexMaxLines(int indexMaxLines) { this.indexMaxLines = indexMaxLines; }

    public int getMaxEntriesPerProject() { return maxEntriesPerProject; }
    public void setMaxEntriesPerProject(int maxEntriesPerProject) { this.maxEntriesPerProject = maxEntriesPerProject; }

    public int getMaxEntriesGlobal() { return maxEntriesGlobal; }
    public void setMaxEntriesGlobal(int maxEntriesGlobal) { this.maxEntriesGlobal = maxEntriesGlobal; }

    public int getProjectExpireDays() { return projectExpireDays; }
    public void setProjectExpireDays(int projectExpireDays) { this.projectExpireDays = projectExpireDays; }

    public int getSubClientTimeout() { return subClientTimeout; }
    public void setSubClientTimeout(int subClientTimeout) { this.subClientTimeout = subClientTimeout; }

    public String getAgentProvider() { return agentProvider; }
    public void setAgentProvider(String agentProvider) { this.agentProvider = agentProvider; }

    public boolean isDreamEnabled() { return dreamEnabled; }
    public void setDreamEnabled(boolean dreamEnabled) { this.dreamEnabled = dreamEnabled; }

    public int getDreamMinHours() { return dreamMinHours; }
    public void setDreamMinHours(int dreamMinHours) { this.dreamMinHours = dreamMinHours; }

    public int getDreamMinSessions() { return dreamMinSessions; }
    public void setDreamMinSessions(int dreamMinSessions) { this.dreamMinSessions = dreamMinSessions; }
}
