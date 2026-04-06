package com.mola.cmd.proxy.app.acp.memory.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆整理操作模型，子 Client 返回的单条整理指令。
 */
public class DreamAction {

    public enum ActionType {
        MERGE, UPDATE, DELETE, NOOP
    }

    private ActionType action;

    // MERGE 专用
    private List<String> sourceIds;
    private MergeResult result;

    // UPDATE / DELETE 共用
    private String id;

    // UPDATE 专用
    private Map<String, String> fields = new HashMap<>();

    // DELETE 专用
    private String reason;

    public ActionType getAction() { return action; }
    public void setAction(ActionType action) { this.action = action; }

    public List<String> getSourceIds() { return sourceIds; }
    public void setSourceIds(List<String> sourceIds) { this.sourceIds = sourceIds; }

    public MergeResult getResult() { return result; }
    public void setResult(MergeResult result) { this.result = result; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Map<String, String> getFields() { return fields; }
    public void setFields(Map<String, String> fields) { this.fields = fields; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    /**
     * MERGE 操作的合并结果。
     */
    public static class MergeResult {
        private String type;
        private String title;
        private String summary;
        private String detail;
        private List<String> tags = new ArrayList<>();

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
}
