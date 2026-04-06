package com.mola.cmd.proxy.app.acp.acpclient;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Kiro CLI 的 AgentProvider 实现。
 * <p>
 * 封装 kiro-cli 特有的命令路径、参数和 MCP 配置文件约定。
 */
public class KiroCliAgentProvider implements AgentProvider {

    private static final String HOME = System.getProperty("user.home");
    private static final String DEFAULT_COMMAND = HOME + "/.local/bin/kiro-cli";
    private static final String[] DEFAULT_ARGS = {"acp"};

    private final String command;
    private final String[] args;

    public KiroCliAgentProvider() {
        this(DEFAULT_COMMAND, DEFAULT_ARGS);
    }

    public KiroCliAgentProvider(String command, String[] args) {
        this.command = command;
        this.args = args;
    }

    @Override
    public String getCommand() {
        return command;
    }

    @Override
    public String[] getArgs() {
        return args;
    }

    @Override
    public List<Path> getMcpConfigPaths(String workspacePath) {
        List<Path> paths = new ArrayList<>();
        // 用户级配置（低优先级）
        paths.add(Paths.get(HOME, ".kiro", "settings", "mcp.json"));
        // 工作目录级配置（高优先级）
        if (workspacePath != null && !workspacePath.trim().isEmpty()) {
            paths.add(Paths.get(workspacePath, ".kiro", "settings", "mcp.json"));
        }
        return paths;
    }

    @Override
    public String getName() {
        return "kiro-cli";
    }
}
