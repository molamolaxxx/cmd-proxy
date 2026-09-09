package com.mola.cmd.proxy.app.acp.gateway;

/** Explicit protocol error mapped consistently by HTTP and WebSocket transports. */
public final class GatewayException extends RuntimeException {
    private final int httpStatus;
    private final String code;
    private final boolean retryable;

    public GatewayException(int httpStatus, String code, String message, boolean retryable) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.retryable = retryable;
    }

    public int getHttpStatus() { return httpStatus; }
    public String getCode() { return code; }
    public boolean isRetryable() { return retryable; }
}
