package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Claude Agent ACP 的 AgentProvider 实现。
 * <p>
 * 通过 {@code npx @agentclientprotocol/claude-agent-acp} 启动适配器子进程，
 * 该适配器将 Claude Agent SDK 包装为标准 ACP JSON-RPC over stdio。
 * <p>
 * 凭证仍由 Claude Agent SDK 的 settings 解析；MCP 配置则由 cmd-proxy 从
 * Claude 的标准 local/project/user scope 读取并随 session/new 传入，以便注入
 * 每个 Agent runtime 独立的鉴权上下文。
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
        List<Path> paths = new ArrayList<>();
        // Claude 的 user/local scope 都保存在 ~/.claude.json：user scope 位于
        // 根 mcpServers，local scope 位于 projects.<cwd>.mcpServers。
        paths.add(Paths.get(HOME, ".claude.json"));
        // project scope 使用工作目录下的 .mcp.json。
        if (workspacePath != null && !workspacePath.trim().isEmpty()) {
            paths.add(Paths.get(workspacePath, ".mcp.json"));
        }
        // 这些配置必须通过 ACP session/new 传入，cmd-proxy 才能为每个 runtime
        // 注入独立的 MCP 鉴权上下文；不能只依赖 Claude SDK 自行加载。
        return paths;
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
        String model = getConfiguredModel(robotParam);
        if (model == null) {
            return Collections.emptyMap();
        }

        Map<String, String> env = new LinkedHashMap<>();
        // Claude Code 中 ANTHROPIC_MODEL 的优先级高于 .claude/settings.json。
        // 先在 SDK Query 创建时指定模型，避免 settings 中的默认模型抢先生效。
        env.put("ANTHROPIC_MODEL", model);
        if (isCustomModel(model)) {
            // 自定义网关模型通常不在 SDK 的内置模型列表中。将它显式加入模型选择器，
            // 后续 session/set_config_option 才能按完整 ID 精确匹配，而不是模糊回退。
            env.put("ANTHROPIC_CUSTOM_MODEL_OPTION", model);
        }
        return env;
    }

    @Override
    public Map<String, String> getInitialSessionConfigOptions(AcpRobotParam robotParam) {
        String model = getConfiguredModel(robotParam);
        if (model == null) {
            return Collections.emptyMap();
        }
        return Collections.singletonMap("model", model);
    }

    private String getConfiguredModel(AcpRobotParam robotParam) {
        if (robotParam == null || robotParam.getModel() == null
                || robotParam.getModel().trim().isEmpty()) {
            return null;
        }
        return robotParam.getModel().trim();
    }

    private boolean isCustomModel(String model) {
        String baseModel = model.endsWith("[1m]")
                ? model.substring(0, model.length() - "[1m]".length())
                : model;
        return !("default".equals(baseModel)
                || "best".equals(baseModel)
                || "sonnet".equals(baseModel)
                || "opus".equals(baseModel)
                || "haiku".equals(baseModel)
                || "opusplan".equals(baseModel)
                || baseModel.startsWith("claude-"));
    }

    @Override
    public boolean needsInlineImages() {
        return true;
    }

    @Override
    public CompactionSignal detectCompactionSignal(JsonObject msg) {
        if (msg == null || !"session/update".equals(getString(msg, "method"))) {
            return CompactionSignal.NONE;
        }

        JsonObject params = msg.getAsJsonObject("params");
        JsonObject update = params != null ? params.getAsJsonObject("update") : null;
        if (update == null) {
            return CompactionSignal.NONE;
        }

        String updateType = getString(update, "sessionUpdate");
        // 当前 claude-agent-acp 将 SDK 的 compact_boundary 转为 usage_update。
        // Client 仅在之前收到 STARTED 时把它认定为压缩完成。
        if ("usage_update".equals(updateType)) {
            return CompactionSignal.CONTEXT_USAGE_REFRESHED;
        }
        if (!"agent_message_chunk".equals(updateType)) {
            return CompactionSignal.NONE;
        }

        JsonObject content = update.getAsJsonObject("content");
        String text = content != null ? getString(content, "text").trim() : "";
        if ("Compacting...".equals(text)) {
            return CompactionSignal.STARTED;
        }
        if ("Compacting completed.".equals(text)) {
            return CompactionSignal.COMPLETED;
        }
        if (text.startsWith("Compacting failed")) {
            return CompactionSignal.FAILED;
        }
        return CompactionSignal.NONE;
    }

    private String getString(JsonObject object, String member) {
        return object != null && object.has(member) && !object.get(member).isJsonNull()
                ? object.get(member).getAsString() : "";
    }
}
