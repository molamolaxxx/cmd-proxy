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
     * Provider 对上下文压缩生命周期的标准化描述。
     * <p>
     * ACP 本身没有统一的 context-compaction 事件；不同 provider 会通过
     * session/update 文本、自定义 notification 或 usage 更新表达该状态。
     */
    enum CompactionSignal {
        NONE,
        STARTED,
        COMPLETED,
        FAILED,
        /**
         * 压缩后刷新上下文用量。仅当此前收到 {@link #STARTED} 时，Client 才应将其视为完成。
         */
        CONTEXT_USAGE_REFRESHED
    }

    /**
     * agent 可执行命令路径，如 ~/.local/bin/kiro-cli
     */
    String getCommand();

    /**
     * agent 命令参数，如 ["acp"]
     */
    String[] getArgs();

    /**
     * 是否提供备用启动命令。主命令启动或初始化失败时，客户端会自动尝试备用命令。
     */
    default boolean hasFallbackCommand() {
        return false;
    }

    /**
     * 备用启动命令，仅当 {@link #hasFallbackCommand()} 为 true 时调用。
     */
    default String getFallbackCommand() {
        throw new UnsupportedOperationException("No fallback command configured");
    }

    /**
     * 备用启动命令参数。
     */
    default String[] getFallbackArgs() {
        return new String[0];
    }

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
     * 从完整 ACP JSON-RPC 消息中识别上下文压缩生命周期事件。
     * <p>
     * 必须接收完整消息而不是仅接收 session/update 的 update 字段：部分 provider
     * （例如 Kiro）使用自定义 JSON-RPC notification 发送压缩状态。
     *
     * @param msg 完整的 JSON-RPC 消息
     * @return 识别出的压缩信号；不支持或无关消息返回 {@link CompactionSignal#NONE}
     */
    default CompactionSignal detectCompactionSignal(JsonObject msg) {
        return CompactionSignal.NONE;
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

    /**
     * 获取 ACP session 创建或恢复后需要立即应用的配置项。
     * <p>
     * 返回值会通过标准 {@code session/set_config_option} 请求逐项设置，适用于
     * 需要在 session 级别选择模型、推理强度等配置的 Agent。
     *
     * @param robotParam robot 配置，可能为 null
     * @return configId 到配置值的映射，空 map 表示无需设置
     */
    default Map<String, String> getInitialSessionConfigOptions(AcpRobotParam robotParam) {
        return Collections.emptyMap();
    }

    /**
     * 是否需要将图片以 base64 image block 内嵌到 prompt 中。
     * 某些 agent 的 Read 工具不支持返回图片内容，需要在 prompt 层面直接传递。
     */
    default boolean needsInlineImages() {
        return false;
    }
}
