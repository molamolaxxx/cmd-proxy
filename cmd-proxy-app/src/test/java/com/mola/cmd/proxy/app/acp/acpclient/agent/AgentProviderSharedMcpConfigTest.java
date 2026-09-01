package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.acpclient.McpConfigLoader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 统一共享 MCP 配置（.cmd-proxy/mcp.json）的加载契约：
 * 所有 Provider 都能读到同一份 JSON 配置，且共享配置优先级低于 Provider 自身配置。
 */
public class AgentProviderSharedMcpConfigTest {

    private static final String WORKSPACE = "/tmp/shared-mcp-workspace";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void kiroNoLongerRecognizesCmdProxySpecificJson() throws Exception {
        Path workspace = temporaryFolder.newFolder("kiro-workspace").toPath();
        Path settings = workspace.resolve(".kiro").resolve("settings");
        Files.createDirectories(settings);
        // 遗留的 mcp-cmd-proxy.json 不应再被识别，路径识别逻辑已移除。
        Files.write(settings.resolve("mcp-cmd-proxy.json"),
                "{\"mcpServers\":{}}".getBytes(StandardCharsets.UTF_8));

        KiroCliAgentProvider provider = new KiroCliAgentProvider();
        List<Path> paths = provider.getMcpConfigPaths(workspace.toString());

        for (Path path : paths) {
            assertFalse("不应再识别 mcp-cmd-proxy.json: " + path,
                    "mcp-cmd-proxy.json".equals(path.getFileName().toString()));
        }
        assertTrue("应保留 Kiro 自身的 mcp.json 配置",
                paths.stream().anyMatch(path ->
                        "mcp.json".equals(path.getFileName().toString())
                                && path.toString().contains(".kiro" + java.io.File.separator + "settings")));
    }

    @Test
    public void everyProviderAppendsSharedConfigsLast() {
        assertSharedSuffix(new KiroCliAgentProvider().getMcpConfigPaths(WORKSPACE), "kiro-cli");
        assertSharedSuffix(new ClaudeAgentAcpProvider().getMcpConfigPaths(WORKSPACE), "claude-agent-acp");
        assertSharedSuffix(new OpenCodeAgentProvider().getMcpConfigPaths(WORKSPACE), "opencode");
        assertSharedSuffix(new CodexAcpProvider().getMcpConfigPaths(WORKSPACE), "codex-acp");
        assertSharedSuffix(new DeepSeekHarnessAcpProvider().getMcpConfigPaths(WORKSPACE), "deepseek-harness-acp");
    }

    private void assertSharedSuffix(List<Path> paths, String providerName) {
        assertTrue(providerName + " 应包含统一共享配置", paths.size() >= 2);
        Path userShared = paths.get(paths.size() - 2);
        Path projectShared = paths.get(paths.size() - 1);
        assertEquals(providerName + " 用户级共享配置应为 mcp.json", "mcp.json",
                userShared.getFileName().toString());
        assertEquals(providerName + " 工作区级共享配置路径错误",
                WORKSPACE + "/.cmd-proxy/mcp.json", projectShared.toString().replace('\\', '/'));
    }

    @Test
    public void sharedConfigNeverOverridesProviderOwnServer() throws Exception {
        Path workspace = temporaryFolder.newFolder("merge-workspace").toPath();
        Path kiroConfig = workspace.resolve(".kiro").resolve("settings").resolve("mcp.json");
        Files.createDirectories(kiroConfig.getParent());
        Files.write(kiroConfig, ("{\"mcpServers\":{" +
                "\"shared\":{\"url\":\"https://kiro/shared\"}," +
                "\"kiro-only\":{\"url\":\"https://kiro/only\"}}}")
                .getBytes(StandardCharsets.UTF_8));
        Path sharedConfig = workspace.resolve(".cmd-proxy").resolve("mcp.json");
        Files.createDirectories(sharedConfig.getParent());
        Files.write(sharedConfig, ("{\"mcpServers\":{" +
                "\"shared\":{\"url\":\"https://shared/override\"}," +
                "\"cmd-proxy-only\":{\"url\":\"https://cmdproxy/only\"}}}")
                .getBytes(StandardCharsets.UTF_8));

        // 模拟 Provider 路径列表：自身配置在前，统一共享配置在后。
        JsonArray servers = McpConfigLoader.loadFromPaths(
                Arrays.asList(kiroConfig, sharedConfig));

        assertEquals("同名 server 应保留 Provider 自身配置",
                "https://kiro/shared", url(servers, "shared"));
        assertEquals("https://kiro/only", url(servers, "kiro-only"));
        assertEquals("共享配置独有的 server 应被加载",
                "https://cmdproxy/only", url(servers, "cmd-proxy-only"));
    }

    private static String url(JsonArray servers, String name) {
        for (int i = 0; i < servers.size(); i++) {
            JsonObject server = servers.get(i).getAsJsonObject();
            if (name.equals(server.get("name").getAsString())) {
                return server.get("url").getAsString();
            }
        }
        throw new AssertionError("Missing MCP server: " + name);
    }
}
