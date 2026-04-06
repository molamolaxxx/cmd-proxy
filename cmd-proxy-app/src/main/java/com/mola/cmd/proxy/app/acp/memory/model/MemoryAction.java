package com.mola.cmd.proxy.app.acp.memory.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 子 Client 返回的单条记忆操作指令。
 */
public class MemoryAction {

    public enum ActionType {
        ADD, UPDATE, DELETE, NOOP
    }

    private ActionType action;
    private String id;          // UPDATE/DELETE 时为已有记忆 ID
    private String type;        // user | feedback | project | reference
    private String title;
    private String summary;
    private String detail;
    private List<String> tags = new ArrayList<>();

    public ActionType getAction() { return action; }
    public void setAction(ActionType action) { this.action = action; }

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

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
}
