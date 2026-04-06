package com.mola.cmd.proxy.app.acp.memory.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆索引文件（MEMORY_INDEX.json）的数据模型。
 */
public class MemoryIndex {

    private int version = 1;
    private String lastUpdated;
    private List<MemoryEntry> memories = new ArrayList<>();

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(String lastUpdated) { this.lastUpdated = lastUpdated; }

    public List<MemoryEntry> getMemories() { return memories; }
    public void setMemories(List<MemoryEntry> memories) { this.memories = memories; }
}
