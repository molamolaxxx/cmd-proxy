package com.mola.cmd.proxy.app.memory.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条记忆的数据模型，同时用于索引和明细。
 */
public class MemoryEntry {

    private String id;
    private String type;        // user | feedback | project | reference
    private String title;
    private String summary;
    private String detail;      // 明细内容（仅写入明细文件时使用）
    private String file;        // 明细文件绝对路径
    private List<String> tags = new ArrayList<>();
    private String createdAt;
    private String updatedAt;
    private String sourceSession;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getFile() { return file; }
    public void setFile(String file) { this.file = file; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public String getSourceSession() { return sourceSession; }
    public void setSourceSession(String sourceSession) { this.sourceSession = sourceSession; }
}
