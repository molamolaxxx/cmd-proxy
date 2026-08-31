package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Kiro CLI 的 AgentProvider 实现。
 * <p>
 * 封装 kiro-cli 特有的命令路径、参数和 MCP 配置文件约定。
 */
public class KiroCliAgentProvider implements AgentProvider {

    private static final String HOME = System.getProperty("user.home");
    private static final String[] DEFAULT_ARGS = {"acp"};

    private final String command;
    private final String[] args;

    public KiroCliAgentProvider() {
        this("kiro-cli", DEFAULT_ARGS);
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
        // 统一共享配置（.cmd-proxy/mcp.json）优先级最低，同名时优先 Kiro 自身配置。
        return appendSharedMcpConfigPaths(paths, workspacePath);
    }

    @Override
    public String getName() {
        return "kiro-cli";
    }

    @Override
    public double extractContextUsage(JsonObject msg) {
        if (!msg.has("method") || !"_kiro.dev/metadata".equals(msg.get("method").getAsString())) {
            return -1;
        }
        JsonObject params = msg.getAsJsonObject("params");
        if (params != null && params.has("contextUsagePercentage")) {
            return params.get("contextUsagePercentage").getAsDouble();
        }
        return -1;
    }

    @Override
    public CompactionSignal detectCompactionSignal(JsonObject msg) {
        if (msg == null || !"_kiro.dev/compaction/status".equals(getString(msg, "method"))) {
            return CompactionSignal.NONE;
        }

        JsonObject params = msg.getAsJsonObject("params");
        // Kiro 的扩展文档只约定该 notification 用于报告压缩进度，未固定公开
        // payload schema。兼容已知的 status/state/phase/result 字段，并保留原始
        // notification 日志，方便在不同 CLI 版本中收紧判断。
        String status = firstNonEmpty(
                getCompactionStatus(params),
                getString(params, "state"),
                getString(params, "phase"),
                getString(params, "result"))
                .toLowerCase(java.util.Locale.ROOT);

        if ("started".equals(status) || "starting".equals(status)
                || "in_progress".equals(status) || "compacting".equals(status)) {
            return CompactionSignal.STARTED;
        }
        if ("completed".equals(status) || "complete".equals(status)
                || "success".equals(status) || "succeeded".equals(status)) {
            return CompactionSignal.COMPLETED;
        }
        if ("failed".equals(status) || "failure".equals(status)
                || "error".equals(status)) {
            return CompactionSignal.FAILED;
        }
        return CompactionSignal.NONE;
    }

    /**
     * Kiro CLI 既出现过字符串状态，也会发送 {"type":"started"} 形式的状态对象。
     */
    private String getCompactionStatus(JsonObject params) {
        if (params == null || !params.has("status") || params.get("status").isJsonNull()) {
            return "";
        }
        if (params.get("status").isJsonObject()) {
            return getString(params.getAsJsonObject("status"), "type");
        }
        return getString(params, "status");
    }

    private String getString(JsonObject object, String member) {
        return object != null && object.has(member) && object.get(member).isJsonPrimitive()
                ? object.get(member).getAsString() : "";
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    @Override
    public List<String> getExtraArgs(AcpRobotParam robotParam) {
        if (robotParam == null || robotParam.getModel() == null || robotParam.getModel().trim().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return Arrays.asList("--model", robotParam.getModel().trim());
    }
}
