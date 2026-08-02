package com.mola.cmd.proxy.app.acp.channel.model;

public final class ChannelSendResult {
    private final boolean success;
    private final String mode;
    private final String error;

    private ChannelSendResult(boolean success, String mode, String error) {
        this.success = success;
        this.mode = mode;
        this.error = error;
    }

    public static ChannelSendResult success(String mode) {
        return new ChannelSendResult(true, mode, null);
    }

    public static ChannelSendResult failure(String error) {
        return new ChannelSendResult(false, null, error);
    }

    public boolean isSuccess() { return success; }
    public String getMode() { return mode; }
    public String getError() { return error; }
}
