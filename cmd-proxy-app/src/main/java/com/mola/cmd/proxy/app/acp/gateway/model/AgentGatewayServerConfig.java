package com.mola.cmd.proxy.app.acp.gateway.model;

/** Shared physical listener configuration for all logical Agent gateways. */
public final class AgentGatewayServerConfig {
    private boolean enabled;
    private String bindHost = "127.0.0.1";
    private int port = 10529;
    private int maxConnections = 100;
    private int eventRetentionDays = 7;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBindHost() { return text(bindHost, "127.0.0.1"); }
    public void setBindHost(String bindHost) { this.bindHost = bindHost; }
    /** Port zero is retained for embedded ephemeral-port tests; ConfigUI rejects it when enabled. */
    public int getPort() { return port < 0 ? 10529 : port; }
    public void setPort(int port) { this.port = port; }
    public int getMaxConnections() { return maxConnections <= 0 ? 100 : maxConnections; }
    public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }
    public int getEventRetentionDays() {
        return eventRetentionDays <= 0 ? 7 : Math.min(eventRetentionDays, 90);
    }
    public void setEventRetentionDays(int eventRetentionDays) {
        this.eventRetentionDays = eventRetentionDays;
    }

    private static String text(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
