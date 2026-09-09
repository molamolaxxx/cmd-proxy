package com.mola.cmd.proxy.app.acp.gateway.model;

/** One authenticated logical gateway bound to exactly one Agent target. */
public final class AgentGatewayConfig {
    private String id;
    private String name;
    private boolean enabled;
    private String authCode;
    private GatewayTarget target;
    private Limits limits;
    private FilePolicy filePolicy;

    public String getId() { return trim(id); }
    public void setId(String id) { this.id = id; }
    public String getName() { return trim(name); }
    public void setName(String name) { this.name = name; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAuthCode() { return authCode == null ? "" : authCode.trim(); }
    public void setAuthCode(String authCode) { this.authCode = authCode; }
    public GatewayTarget getTarget() { return target; }
    public void setTarget(GatewayTarget target) { this.target = target; }
    public Limits getLimits() { return limits == null ? new Limits() : limits; }
    public void setLimits(Limits limits) { this.limits = limits; }
    public FilePolicy getFilePolicy() {
        return filePolicy == null ? new FilePolicy() : filePolicy;
    }
    public void setFilePolicy(FilePolicy filePolicy) { this.filePolicy = filePolicy; }

    public void validate(String instanceId) {
        if (getId().isEmpty() || getId().length() > 120) {
            throw new IllegalArgumentException("Agent gateway id is invalid");
        }
        if (getName().isEmpty() || getName().length() > 200) {
            throw new IllegalArgumentException("Agent gateway name is invalid");
        }
        if (getAuthCode().length() < 32 || getAuthCode().length() > 512) {
            throw new IllegalArgumentException("Agent gateway auth code must contain 32-512 characters");
        }
        if (target == null) throw new IllegalArgumentException("Agent gateway target is required");
        target.validate(instanceId);
    }

    public static final class Limits {
        private int requestsPerMinute = 60;
        private int maxConnections = 5;
        private int maxFileCount = 10;
        private long maxFileBytes = 20L * 1024L * 1024L;
        private long maxTotalFileBytes = 50L * 1024L * 1024L;

        public int getRequestsPerMinute() {
            return requestsPerMinute <= 0 ? 60 : Math.min(requestsPerMinute, 10000);
        }
        public void setRequestsPerMinute(int requestsPerMinute) {
            this.requestsPerMinute = requestsPerMinute;
        }
        public int getMaxConnections() {
            return maxConnections <= 0 ? 5 : Math.min(maxConnections, 100);
        }
        public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }
        public int getMaxFileCount() {
            return maxFileCount <= 0 ? 10 : Math.min(maxFileCount, 20);
        }
        public void setMaxFileCount(int maxFileCount) { this.maxFileCount = maxFileCount; }
        public long getMaxFileBytes() {
            return maxFileBytes <= 0 ? 20L * 1024L * 1024L : maxFileBytes;
        }
        public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
        public long getMaxTotalFileBytes() {
            return maxTotalFileBytes <= 0 ? 50L * 1024L * 1024L : maxTotalFileBytes;
        }
        public void setMaxTotalFileBytes(long maxTotalFileBytes) {
            this.maxTotalFileBytes = maxTotalFileBytes;
        }
    }

    public static final class FilePolicy {
        private java.util.List<String> allowedSchemes;
        private java.util.List<String> allowedHosts;
        private boolean allowPrivateAddress;

        public java.util.List<String> getAllowedSchemes() {
            return allowedSchemes == null || allowedSchemes.isEmpty()
                    ? java.util.Collections.singletonList("https")
                    : java.util.Collections.unmodifiableList(allowedSchemes);
        }
        public void setAllowedSchemes(java.util.List<String> allowedSchemes) {
            this.allowedSchemes = allowedSchemes;
        }
        public java.util.List<String> getAllowedHosts() {
            return allowedHosts == null ? java.util.Collections.emptyList()
                    : java.util.Collections.unmodifiableList(allowedHosts);
        }
        public void setAllowedHosts(java.util.List<String> allowedHosts) {
            this.allowedHosts = allowedHosts;
        }
        public boolean isAllowPrivateAddress() { return allowPrivateAddress; }
        public void setAllowPrivateAddress(boolean allowPrivateAddress) {
            this.allowPrivateAddress = allowPrivateAddress;
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
