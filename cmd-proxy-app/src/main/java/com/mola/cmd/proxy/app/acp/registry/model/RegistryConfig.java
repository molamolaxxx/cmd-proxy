package com.mola.cmd.proxy.app.acp.registry.model;

import java.net.URI;

/** 注册服务独立于业务配置，保存后立即应用。 */
public final class RegistryConfig {
    public boolean serverEnabled;
    public int tunnelPort = 10530;
    public String serverCredential = "";
    public boolean clientEnabled;
    public String centerUrl = "";
    public String clientCredential = "";
    public String displayName = "";
    public String nodeId = java.util.UUID.randomUUID().toString();

    public void validate() {
        if (tunnelPort < 1 || tunnelPort > 65535) throw new IllegalArgumentException("隧道端口必须在 1～65535 之间");
        if (serverEnabled && serverCredential.length() < 32) throw new IllegalArgumentException("中心注册凭证至少 32 个字符");
        if (displayName.length() > 120) throw new IllegalArgumentException("环境名称不能超过 120 个字符");
        if (clientEnabled) {
            centerUrl = normalizeUrl(centerUrl);
            if (clientCredential.length() < 32) throw new IllegalArgumentException("请填写中心的注册凭证");
        }
        if (!nodeId.matches("[a-zA-Z0-9-]{1,80}")) throw new IllegalArgumentException("无效的注册身份");
    }

    public static String normalizeUrl(String value) {
        String raw = value == null ? "" : value.trim();
        if (!raw.contains("://")) raw = "http://" + raw;
        URI uri = URI.create(raw);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("中心地址请填写 IP:端口 或 http(s)://域名:端口");
        }
        return raw.replaceAll("/+$", "");
    }
}
