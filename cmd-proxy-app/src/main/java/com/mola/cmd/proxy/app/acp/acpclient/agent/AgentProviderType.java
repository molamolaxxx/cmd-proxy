package com.mola.cmd.proxy.app.acp.acpclient.agent;

/**
 * Agent 提供者类型枚举。
 * 每种类型对应一个 {@link AgentProvider} 实现。
 */
public enum AgentProviderType {

    KIRO_CLI;

    /**
     * 从字符串解析，不区分大小写，无法匹配时返回默认值 KIRO_CLI。
     */
    public static AgentProviderType fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return KIRO_CLI;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return KIRO_CLI;
        }
    }
}
