package com.mola.cmd.proxy.app.acp.acpclient.agent;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenCode 的 AgentProvider 实现。
 * <p>
 * opencode 通过 {@code opencode acp} 启动 ACP 子进程，
 * MCP servers 由 opencode 自身配置管理，无需 Client 端传入。
 */
public class OpenCodeAgentProvider implements AgentProvider {

    private static final String HOME = System.getProperty("user.home");

    @Override
    public String getCommand() {
        return "opencode";
    }

    @Override
    public String[] getArgs() {
        return new String[]{"acp"};
    }

    @Override
    public List<Path> getMcpConfigPaths(String workspacePath) {
        List<Path> paths = new ArrayList<>();
        // 全局配置（低优先级）
        paths.add(Paths.get(HOME, ".config", "opencode", "opencode.json"));
        // 项目级配置（高优先级）
        if (workspacePath != null && !workspacePath.trim().isEmpty()) {
            paths.add(Paths.get(workspacePath, "opencode.json"));
        }
        return paths;
    }

    @Override
    public String getName() {
        return "opencode";
    }

    @Override
    public String getSkillsRelativePath() {
        return ".opencode/skills";
    }
}
