package com.mola.cmd.proxy.app.acp.acpclient.context;

import com.google.gson.JsonObject;

/**
 * ACP 会话上下文中的单条消息记录。
 * <p>
 * 支持三种角色：
 * <ul>
 *   <li>{@link Role#USER} — 用户输入</li>
 *   <li>{@link Role#ASSISTANT} — agent 回答</li>
 *   <li>{@link Role#TOOL} — 工具调用及结果</li>
 * </ul>
 */
public class ContextMessage {

    public enum Role {
        USER, ASSISTANT, TOOL
    }

    private final Role role;
    private final String content;

    // ---- TOOL 专用字段 ----
    private final String toolCallId;
    private final String toolName;
    private final String status;
    private final JsonObject rawInput;
    private final JsonObject rawOutput;

    /** 构造 USER / ASSISTANT 消息 */
    public ContextMessage(Role role, String content) {
        this.role = role;
        this.content = content;
        this.toolCallId = null;
        this.toolName = null;
        this.status = null;
        this.rawInput = null;
        this.rawOutput = null;
    }

    /** 构造 TOOL 消息 */
    public ContextMessage(String toolCallId, String toolName, String status,
                          JsonObject rawInput, JsonObject rawOutput) {
        this.role = Role.TOOL;
        this.content = null;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.status = status;
        this.rawInput = rawInput;
        this.rawOutput = rawOutput;
    }

    public Role getRole() { return role; }
    public String getContent() { return content; }
    public String getToolCallId() { return toolCallId; }
    public String getToolName() { return toolName; }
    public String getStatus() { return status; }
    public JsonObject getRawInput() { return rawInput; }
    public JsonObject getRawOutput() { return rawOutput; }

    @Override
    public String toString() {
        if (role == Role.TOOL) {
            return String.format("ContextMessage{role=TOOL, toolName='%s', toolCallId='%s', status='%s'}",
                    toolName, toolCallId, status);
        }
        String preview = content != null && content.length() > 80
                ? content.substring(0, 80) + "..." : content;
        return String.format("ContextMessage{role=%s, content='%s'}", role, preview);
    }
}
