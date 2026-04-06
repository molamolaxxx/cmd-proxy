package com.mola.cmd.proxy.app.acp.acpclient.agent;

import java.util.EnumMap;
import java.util.Map;

/**
 * AgentProvider 路由器，根据 {@link AgentProviderType} 返回对应的 {@link AgentProvider} 实例。
 * <p>
 * 单例，内部缓存实例避免重复创建。
 */
public class AgentProviderRouter {

    private static final AgentProviderRouter INSTANCE = new AgentProviderRouter();

    private final Map<AgentProviderType, AgentProvider> providers = new EnumMap<>(AgentProviderType.class);

    private AgentProviderRouter() {
        providers.put(AgentProviderType.KIRO_CLI, new KiroCliAgentProvider());
    }

    public static AgentProviderRouter getInstance() {
        return INSTANCE;
    }

    /**
     * 根据类型获取 AgentProvider，未注册时返回默认（KIRO_CLI）。
     */
    public AgentProvider resolve(AgentProviderType type) {
        return providers.getOrDefault(type, providers.get(AgentProviderType.KIRO_CLI));
    }

    /**
     * 从字符串解析并路由。
     */
    public AgentProvider resolve(String typeName) {
        return resolve(AgentProviderType.fromString(typeName));
    }
}
