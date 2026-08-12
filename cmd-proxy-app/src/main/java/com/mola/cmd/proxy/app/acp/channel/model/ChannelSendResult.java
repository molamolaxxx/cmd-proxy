package com.mola.cmd.proxy.app.acp.channel.model;

public final class ChannelSendResult {
    private final boolean success;
    private final String mode;
    private final String error;
    private final boolean attempted;

    private ChannelSendResult(boolean success, String mode, String error, boolean attempted) {
        this.success = success;
        this.mode = mode;
        this.error = error;
        this.attempted = attempted;
    }

    public static ChannelSendResult success(String mode) {
        return new ChannelSendResult(true, mode, null, true);
    }

    public static ChannelSendResult failure(String error) {
        return new ChannelSendResult(false, null, error, true);
    }

    public static ChannelSendResult notAttempted(String error) {
        return new ChannelSendResult(false, null, error, false);
    }

    public boolean isSuccess() { return success; }
    public String getMode() { return mode; }
    public String getError() { return error; }
    public boolean wasAttempted() { return attempted; }
}
