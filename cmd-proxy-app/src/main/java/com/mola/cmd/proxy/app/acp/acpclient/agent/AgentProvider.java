package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

    /**
     * 从 JSON-RPC 消息中提取 context usage 百分比。
     * 不同 agent 实现有各自的上下文使用量推送格式，由子类覆写解析逻辑。
     *
     * @param msg 完整的 JSON-RPC 消息
     * @return context usage 百分比（0~100），无法提取时返回 -1
     */
    default double extractContextUsage(JsonObject msg) {
        return -1;
    }

    /**
     * agent 的 skills 目录相对于 workspacePath 的路径。
     * 如 kiro-cli 为 ".kiro/skills"，opencode 为 ".opencode/skills"。
     */
    default String getSkillsRelativePath() {
        return ".kiro/skills";
    }

    /**
     * 根据 robot 配置返回额外的命令行参数。
     * 如 Kiro CLI 通过 --model 参数指定模型。
     *
     * @param robotParam robot 配置，可能为 null
     * @return 额外参数列表，空列表表示无额外参数
     */
    default List<String> getExtraArgs(AcpRobotParam robotParam) {
        return Collections.emptyList();
    }

    /**
     * 根据 robot 配置返回额外的环境变量。
     * 如 OpenCode 通过 OPENCODE_CONFIG_CONTENT 注入模型配置。
     *
     * @param robotParam robot 配置，可能为 null
     * @return 额外环境变量 map，空 map 表示无额外环境变量
     */
    default Map<String, String> getExtraEnv(AcpRobotParam robotParam) {
        return Collections.emptyMap();
    }
}
