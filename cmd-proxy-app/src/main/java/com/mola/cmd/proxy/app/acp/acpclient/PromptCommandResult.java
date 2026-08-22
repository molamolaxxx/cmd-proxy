package com.mola.cmd.proxy.app.acp.acpclient;

/** Structured result shared by ordinary MAIN prompt send and cancel commands. */
public final class PromptCommandResult {
    private final boolean accepted;
    private final String code;
    private final String result;

    private PromptCommandResult(boolean accepted, String code, String result) {
        this.accepted = accepted;
        this.code = code;
        this.result = result;
    }

    public static PromptCommandResult accepted(String code, String result) {
        return new PromptCommandResult(true, code, result);
    }

    public static PromptCommandResult rejected(String code, String result) {
        return new PromptCommandResult(false, code, result);
    }

    public boolean isAccepted() { return accepted; }
    public String getCode() { return code; }
    public String getResult() { return result; }
}
