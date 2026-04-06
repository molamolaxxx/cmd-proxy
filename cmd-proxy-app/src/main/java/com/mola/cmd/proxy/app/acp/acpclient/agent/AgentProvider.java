package com.mola.cmd.proxy.app.acp.acpclient.agent;

import java.nio.file.Path;
import java.util.List;

/**
 * Agent 提供者接口，抽象底层 agent CLI 的配置。
 * <p>
 * 将 command、args、MCP 配置路径等与具体 agent 实现（如 kiro-cli）绑定的内容
 * 统一收口到此接口，使 AcpClient 体系与具体 agent 解耦。
 * <p>
 * 不同的 agent 实现只需提供此接口的实现类即可接入。
 */
public interface AgentProvider {

    /**
     * agent 可执行命令路径，如 ~/.local/bin/kiro-cli
     */
    String getCommand();

    /**
     * agent 命令参数，如 ["acp"]
     */
    String[] getArgs();

    /**
     * 获取 MCP 配置文件路径列表，按优先级从低到高排列。
     *
     * @param workspacePath 当前工作目录
     * @return MCP 配置文件路径列表
     */
    List<Path> getMcpConfigPaths(String workspacePath);

    /**
     * agent 的显示名称，用于日志和调试。
     */
    String getName();
}
