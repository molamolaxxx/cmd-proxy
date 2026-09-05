package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenCode 的 AgentProvider 实现。
 * <p>
 * opencode 通过 {@code opencode acp} 启动 ACP 子进程。cmd-proxy 读取其 MCP 配置，
 * 与运行时内置 MCP 一起通过 ACP {@code session/new}/{@code session/load} 显式传入。
 */
public class OpenCodeAgentProvider implements AgentProvider {

    private static final String HOME = System.getProperty("user.home");
    private static final NpmProviderRuntimeManager RUNTIME_MANAGER =
            NpmProviderRuntimeManager.getInstance();

    @Override
    public String getCommand() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "opencode.cmd" : "opencode";
    }

    @Override
    public String getCommand(AcpRobotParam robotParam, Map<String, String> environment) {
        return RUNTIME_MANAGER.preparedCommand(environment);
    }

    @Override
    public void prepareLaunch(AcpRobotParam robotParam, Map<String, String> environment)
            throws java.io.IOException {
        RUNTIME_MANAGER.prepareLaunch(AgentProviderType.OPENCODE, robotParam, environment);
        environment.put("OPENCODE_DISABLE_AUTOUPDATE", "true");
    }

    @Override
    public boolean requiresSerializedWorkspaceLaunch() {
        return true;
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
        // 统一共享配置（.cmd-proxy/mcp.json）优先级最低，同名时优先 OpenCode 自身配置。
        return appendSharedMcpConfigPaths(paths, workspacePath);
    }

    @Override
    public List<Path> getSkillPaths(String workspacePath, AcpRobotParam robotParam) {
        List<Path> paths = new ArrayList<>();
        paths.add(Paths.get(HOME, ".config", "opencode", "skills"));
        if (workspacePath != null && !workspacePath.trim().isEmpty()) {
            paths.add(Paths.get(workspacePath, ".opencode", "skills"));
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

    @Override
    public Map<String, String> getExtraEnv(AcpRobotParam robotParam) {
        if (robotParam == null || robotParam.getModel() == null || robotParam.getModel().trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> env = new HashMap<>();
        env.put("OPENCODE_CONFIG_CONTENT", "{\"model\":\"" + robotParam.getModel().trim() + "\"}");
        return env;
    }
}
