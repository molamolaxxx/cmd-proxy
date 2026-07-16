package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Codex-ACP 的 AgentProvider 实现。
 * <p>
 * 兼容两个版本：
 * <ul>
 *   <li>Node.js 版（@agentclientprotocol/codex-acp）：认证 methodId = "api-key"</li>
 *   <li>Rust 版（zed-industries/codex-acp）：认证 methodId = "openai-api-key"</li>
 * </ul>
 * <p>
 * API Key 通过环境变量 OPENAI_API_KEY 注入，不通过 authenticate 请求体传递。
 * 版本区分由 AbstractAcpClient 在 initialize 响应的 authMethods 中自动检测。
 */
public class CodexAcpProvider implements AgentProvider {

    private static final Gson GSON = new Gson();
    private static final String NPX_PACKAGE = "@agentclientprotocol/codex-acp";
    private static final String HOME = System.getProperty("user.home");

    @Override
    public String getCommand() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "npx.cmd" : "npx";
    }

    @Override
    public String[] getArgs() {
        return new String[]{"-y", NPX_PACKAGE};
    }

    @Override
    public boolean hasFallbackCommand() {
        return true;
    }

    @Override
    public String getFallbackCommand() {
        return "codex-acp";
    }

    @Override
    public String[] getFallbackArgs() {
        return new String[0];
    }

    @Override
    public List<Path> getMcpConfigPaths(String workspacePath) {
        // 先加载全局配置；McpConfigLoader 会按 server 名去重，保留先加载的配置。
        // 这使同名的项目级配置不会在 ACP 会话中重复注册。
        List<Path> paths = new ArrayList<>();
        paths.add(Paths.get(HOME, ".codex", "config.toml"));
        if (workspacePath != null && !workspacePath.trim().isEmpty()) {
            // Codex 的项目级 MCP 配置位于 <workspace>/.codex/config.toml。
            // 显式传入 ACP，避免依赖 codex-acp/App Server 对项目配置层的隐式加载，
            // 并保证 session/load 恢复时仍会重新挂载这些 MCP server。
            paths.add(Paths.get(workspacePath, ".codex", "config.toml"));
        }
        return paths;
    }

    @Override
    public String getName() {
        return "codex-acp";
    }

    @Override
    public String getSkillsRelativePath() {
        return ".agents/skills";
    }

    @Override
    public CompactionSignal detectCompactionSignal(JsonObject msg) {
        if (!isSessionUpdate(msg)) {
            return CompactionSignal.NONE;
        }

        JsonObject update = msg.getAsJsonObject("params").getAsJsonObject("update");
        if (!"agent_message_chunk".equals(getString(update, "sessionUpdate"))) {
            return CompactionSignal.NONE;
        }

        JsonObject content = update.getAsJsonObject("content");
        String text = content != null ? getString(content, "text").trim() : "";
        if ("*Context compacted to fit the model's context window.*".equals(text)
                || "Context compacted.".equals(text)) {
            return CompactionSignal.COMPLETED;
        }
        return CompactionSignal.NONE;
    }

    private boolean isSessionUpdate(JsonObject msg) {
        if (msg == null || !"session/update".equals(getString(msg, "method"))) {
            return false;
        }
        JsonObject params = msg.getAsJsonObject("params");
        return params != null && params.getAsJsonObject("update") != null;
    }

    private String getString(JsonObject object, String member) {
        return object != null && object.has(member) && !object.get(member).isJsonNull()
                ? object.get(member).getAsString() : "";
    }

    @Override
    public Map<String, String> getExtraEnv(AcpRobotParam robotParam) {
        if (robotParam == null) {
            return Collections.emptyMap();
        }
        Map<String, String> env = new HashMap<>();
        String apiKey = robotParam.getApiKey();
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            String trimmedApiKey = apiKey.trim();
            env.put("CODEX_API_KEY", trimmedApiKey);
            env.put("OPENAI_API_KEY", trimmedApiKey);
        }
        String model = robotParam.getModel();
        if (model != null && !model.trim().isEmpty()) {
            env.put("CODEX_CONFIG", buildCodexConfig(model.trim()));
        }
        return env;
    }

    private String buildCodexConfig(String model) {
        JsonObject config = readExistingCodexConfig();
        String modelId = model;
        String reasoningEffort = null;

        int bracketStart = model.lastIndexOf('[');
        if (bracketStart > 0 && model.endsWith("]")) {
            String parsedModelId = model.substring(0, bracketStart).trim();
            String parsedReasoningEffort = model.substring(bracketStart + 1, model.length() - 1).trim();
            if (!parsedModelId.isEmpty() && !parsedReasoningEffort.isEmpty()) {
                modelId = parsedModelId;
                reasoningEffort = parsedReasoningEffort;
            }
        }

        config.addProperty("model", modelId);
        if (reasoningEffort != null) {
            config.addProperty("model_reasoning_effort", reasoningEffort);
        }
        return GSON.toJson(config);
    }

    private JsonObject readExistingCodexConfig() {
        String existing = System.getenv("CODEX_CONFIG");
        if (existing == null || existing.trim().isEmpty()) {
            return new JsonObject();
        }
        try {
            com.google.gson.JsonElement parsed = JsonParser.parseString(existing);
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (Exception ignored) {
            // Fall back to a fresh config; codex-acp requires CODEX_CONFIG to be a JSON object.
        }
        return new JsonObject();
    }
}
