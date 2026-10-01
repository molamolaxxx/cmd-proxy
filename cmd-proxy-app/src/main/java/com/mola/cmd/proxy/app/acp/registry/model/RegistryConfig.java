package com.mola.cmd.proxy.app.acp.registry.model;

import java.net.URI;

/** 注册服务独立于业务配置，保存后立即应用。 */
public final class RegistryConfig {
    public boolean serverEnabled;
    public int tunnelPort = 10530;
    public boolean clientEnabled;
    public String centerUrl = "";
    public String displayName = "";
    public String nodeId = java.util.UUID.randomUUID().toString();

    public void validate() {
        if (tunnelPort < 1 || tunnelPort > 65535) throw new IllegalArgumentException("隧道端口必须在 1～65535 之间");
        if (displayName.length() > 120) throw new IllegalArgumentException("环境名称不能超过 120 个字符");
        if (clientEnabled) {
            centerUrl = normalizeUrl(centerUrl);
        }
        if (!nodeId.matches("[a-zA-Z0-9-]{1,80}")) throw new IllegalArgumentException("无效的注册身份");
    }

    public static String normalizeUrl(String value) {
        String raw = value == null ? "" : value.trim();
        if (!raw.contains("://")) raw = "http://" + raw;
        URI uri;
        try { uri = URI.create(raw); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("中心地址格式不正确，请填写 IP:端口 或 http(s)://域名:端口"); }
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
