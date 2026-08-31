package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.common.PathUtils;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek Harness 的编辑器向 ACP Provider。
 *
 * 使用 OpenMA community adapter {@code @openma/deepseek-harness-acp}，因为官方
 * {@code @deepseek-ai/dsh-acp} 是 automation-only：不接受非空 mcpServers，也不提供
 * cmd-proxy 主聊天链路需要的 rich session/update 和 session/load。
 * dsh/pnpm 安装在 cmd-proxy 管理的 runtime 目录，ACP adapter 按隔离的 DSH_HOME
 * 初始化一次 profile，后续启动只执行 {@code dsh --profile acp}。
 */
public class DeepSeekHarnessAcpProvider implements AgentProvider {

    private static final DeepSeekHarnessRuntimeInstaller RUNTIME_INSTALLER =
            new DeepSeekHarnessRuntimeInstaller();
    private static final NpmProviderRuntimeManager NPM_RUNTIME_MANAGER =
            NpmProviderRuntimeManager.getInstance();

    @Override
    public String getCommand() {
        // prepareLaunch 会保证私有 runtime 已安装。这里必须使用绝对路径：
        // ProcessBuilder.environment() 中追加的 PATH 只属于子进程，不能可靠地用于
        // ProcessBuilder.start() 解析当前 executable。
        return managedRuntimeCommand("dsh").toString();
    }

    @Override
    public String getCommand(AcpRobotParam robotParam, Map<String, String> environment) {
        return NPM_RUNTIME_MANAGER.preparedCommand(environment);
    }

    @Override
    public String[] getArgs() {
        return new String[]{"--profile", "acp"};
    }

    @Override
    public List<Path> getMcpConfigPaths(String workspacePath) {
        // DeepSeek Harness 没有自身格式的 MCP 配置，只加载统一共享配置
        // （$CMD_PROXY_HOME/mcp.json 与 <workspace>/.cmd-proxy/mcp.json）。
        return appendSharedMcpConfigPaths(Collections.emptyList(), workspacePath);
    }

    @Override
    public String getName() {
        return "deepseek-harness-acp";
    }

    @Override
    public String getSkillsRelativePath() {
        return ".agents/skills";
    }

    @Override
    public Map<String, String> getExtraEnv(AcpRobotParam robotParam) {
        Map<String, String> env = new LinkedHashMap<>();
        if (robotParam != null) {
            putIfPresent(env, "DEEPSEEK_API_KEY", robotParam.getApiKey());
            putIfPresent(env, "DEEPSEEK_BASE_URL", robotParam.getDeepSeekBaseUrl());
        }
        env.put("DSH_HOME", resolveDshHome(robotParam).toString());
        env.put("DSH_PERMISSION_MODE", "workspace-write");
        return env;
    }

    @Override
    public void prepareLaunch(AcpRobotParam robotParam, Map<String, String> environment)
            throws java.io.IOException {
        NPM_RUNTIME_MANAGER.prepareLaunch(
                AgentProviderType.DEEPSEEK_HARNESS_ACP, robotParam, environment);
        String resolvedVersion = environment.get(NpmProviderRuntimeManager.ENV_VERSION);
        Path runtimeHome = NPM_RUNTIME_MANAGER.runtimeHome(
                AgentProviderType.DEEPSEEK_HARNESS_ACP, resolvedVersion);
        String managedBin = managedRuntimeBin(runtimeHome).toString();
        String currentPath = environment.get("PATH");
        if (currentPath == null || currentPath.trim().isEmpty()) {
            environment.put("PATH", managedBin);
        } else if (!containsPath(currentPath, managedBin)) {
            environment.put("PATH", managedBin + File.pathSeparator + currentPath);
        }
        RUNTIME_INSTALLER.ensureInstalled(runtimeHome, resolveDshHome(robotParam), environment);
    }

    @Override
    public Map<String, String> getInitialSessionConfigOptions(AcpRobotParam robotParam) {
        if (robotParam == null) {
            return Collections.emptyMap();
        }
        Map<String, String> options = new LinkedHashMap<>();
        if (!isBlank(robotParam.getDshAgentPreset())) {
            options.put("agent", robotParam.getDshAgentPreset().trim());
        }
        if (!isBlank(robotParam.getModel())) {
            options.put("model", robotParam.getModel().trim());
        }
        return options;
    }

    /**
     * 读取当前 Robot 的 ACP profile 实际 bundle 顺序，供 ConfigUI 只读展示。
     * 不触发运行时/profile 安装，也不修改 manifest。
     */
    public List<String> getInstalledProfileBundles(AcpRobotParam robotParam) throws IOException {
        Path manifest = resolveDshHome(robotParam).resolve("profiles").resolve("acp")
                .resolve("package.json");
        if (!Files.isRegularFile(manifest)) {
            return Collections.emptyList();
        }
        try (BufferedReader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IOException("DSH profile package.json 不是 JSON 对象: " + manifest);
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonObject dsh = object(root, "dsh");
            JsonObject profile = object(dsh, "profile");
            JsonArray bundles = array(profile, "bundles");
            if (bundles == null) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<>();
            for (JsonElement bundle : bundles) {
                if (bundle.isJsonPrimitive() && bundle.getAsJsonPrimitive().isString()) {
                    String name = bundle.getAsString();
                    if (!isBlank(name)) {
                        result.add(name.trim());
                    }
                }
            }
            return Collections.unmodifiableList(result);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("读取 DSH profile bundles 失败: " + manifest, e);
        }
    }

    @Override
    public String getSessionCloseMethod() {
        return "session/close";
    }

    @Override
    public boolean closeInputAfterSessionClose() {
        return true;
    }

    @Override
    public PermissionPolicy getPermissionPolicy(AcpRobotParam robotParam) {
        String configured = robotParam != null ? robotParam.getPermissionPolicy() : null;
        return PermissionPolicy.fromString(configured, PermissionPolicy.REJECT);
    }

    Path resolveDshHome(AcpRobotParam robotParam) {
        if (robotParam != null && !isBlank(robotParam.getDshHome())) {
            return expandUserHome(robotParam.getDshHome().trim());
        }
        String robotName = robotParam != null && !isBlank(robotParam.getName())
                ? robotParam.getName().trim() : "default";
        return CmdProxyHome.resolve("dsh", PathUtils.sanitizePath(robotName))
                .toAbsolutePath().normalize();
    }

    Path managedRuntimeHome() {
        return CmdProxyHome.resolve("runtimes", "deepseek-harness")
                .toAbsolutePath().normalize();
    }

    Path managedRuntimeCommand(String command) {
        return managedRuntimeBin(managedRuntimeHome()).resolve(platformCommand(command));
    }

    private Path managedRuntimeBin(Path runtimeHome) {
        return isWindows() ? runtimeHome : runtimeHome.resolve("bin");
    }

    private boolean containsPath(String pathValue, String expected) {
        for (String entry : pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (expected.equals(entry)) {
                return true;
            }
        }
        return false;
    }

    private String platformCommand(String command) {
        return isWindows() ? command + ".cmd" : command;
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private Path expandUserHome(String value) {
        if ("~".equals(value)) {
            return Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return Paths.get(System.getProperty("user.home"), value.substring(2))
                    .toAbsolutePath().normalize();
        }
        return Paths.get(value).toAbsolutePath().normalize();
    }

    private void putIfPresent(Map<String, String> env, String name, String value) {
        if (!isBlank(value)) {
            env.put(name, value.trim());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private JsonObject object(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(name);
    }

    private JsonArray array(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonArray()) {
            return null;
        }
        return parent.getAsJsonArray(name);
    }
}
