package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Claude Agent ACP 的 AgentProvider 实现。
 * <p>
 * 通过 {@code npx @agentclientprotocol/claude-agent-acp} 启动适配器子进程，
 * 该适配器将 Claude Agent SDK 包装为标准 ACP JSON-RPC over stdio。
 * <p>
 * 凭证与 MCP 配置均通过 Claude Agent SDK 的 resolveSettings() 自动加载，
 * 无需 cmd-proxy 手动注入。
 */
public class ClaudeAgentAcpProvider implements AgentProvider {

    private static final String HOME = System.getProperty("user.home");

    @Override
    public String getCommand() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "npx.cmd" : "npx";
    }

    @Override
    public String[] getArgs() {
        return new String[]{"-y", "@agentclientprotocol/claude-agent-acp"};
    }

    @Override
    public List<Path> getMcpConfigPaths(String workspacePath) {
        // Claude Code 通过插件市场管理 MCP server
        // (~/.claude/plugins/marketplaces/.../<plugin>/.mcp.json)
        // 适配器内部 resolveSettings() 自动加载，无需手动注入
        return Collections.emptyList();
    }

    @Override
    public String getName() {
        return "claude-agent-acp";
    }

    @Override
    public String getSkillsRelativePath() {
        // 新格式（推荐）：<project>/.claude/skills/<name>/
        // 旧格式（兼容）：<project>/.claude/commands/<name>/SKILL.md
        return ".claude/skills";
    }

    @Override
    public Map<String, String> getExtraEnv(AcpRobotParam robotParam) {
        return Collections.emptyMap();
    }
}
