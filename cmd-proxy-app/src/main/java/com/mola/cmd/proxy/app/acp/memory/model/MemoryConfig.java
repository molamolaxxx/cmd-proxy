package com.mola.cmd.proxy.app.acp.memory.model;

/**
 * 记忆系统配置，对应 acpConfig.json 中的 "memory" 字段。
 * 所有字段均有默认值，未配置时向后兼容。
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
}
