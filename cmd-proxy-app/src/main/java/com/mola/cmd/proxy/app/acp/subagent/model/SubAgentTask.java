package com.mola.cmd.proxy.app.acp.subagent.model;

/**
 * 主 Agent LLM 通过 dispatch_subagent 指令发出的单个子任务。
 */
public class SubAgentTask {

    /** 目标子 Agent 名称 */
    private String agent;

    /** 任务简短标题，用于区分同一 agent 的不同任务 */
    private String title;

    /** 发送给子 Agent 的 prompt */
    private String prompt;

    public SubAgentTask() {}

    public SubAgentTask(String agent, String title, String prompt) {
        this.agent = agent;
        this.title = title;
        this.prompt = prompt;
    }

    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    /**
     * 用于展示的标识：agent/title 或仅 agent。
     */
    public String getDisplayName() {
        if (title != null && !title.isEmpty()) {
            return agent + "/" + title;
        }
        return agent;
    }
}
