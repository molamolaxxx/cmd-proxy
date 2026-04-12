package com.mola.cmd.proxy.app.acp.subagent.model;

/**
 * 单个子 Agent 的执行结果。
 */
public class SubAgentResult {

    public enum Status { SUCCESS, ERROR, TIMEOUT }

    private String agent;
    private Status status;
    private String response;
    private String errorMessage;
    private long durationMs;

    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getResponse() { return response; }
    public void setResponse(String response) { this.response = response; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
}
