package com.mola.cmd.proxy.app.acp.acpclient.model;

import com.alibaba.fastjson.JSONObject;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AgentModelCatalogTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void mergesAcpDiscoveryConfiguredValuesAndPersistentHistory() throws Exception {
        Path file = temporaryFolder.newFolder("catalog").toPath().resolve("models.json");
        AgentModelCatalog catalog = new AgentModelCatalog(file);
        AcpRobotParam robot = new AcpRobotParam();
        robot.setAgentProvider("CODEX_ACP");
        robot.setProviderVersion("1.2.3");
        robot.setWorkDir("/workspace/demo");

        JsonObject response = JsonParser.parseString("{\"result\":{\"sessionId\":\"s1\","
                + "\"configOptions\":[{\"id\":\"model\",\"category\":\"model\","
                + "\"type\":\"select\",\"currentValue\":\"gpt-live\",\"options\":["
                + "{\"value\":\"gpt-live\",\"name\":\"GPT Live\"},"
                + "{\"group\":\"legacy\",\"name\":\"Legacy\",\"options\":["
                + "{\"value\":\"gpt-old\",\"name\":\"GPT Old\"}]}]}],"
                + "\"models\":{\"availableModels\":[{\"modelId\":\"legacy-model\","
                + "\"name\":\"Legacy Model\"}]}}}")
                .getAsJsonObject();
        catalog.recordAcpResponse(robot, response);

        JSONObject request = request();
        request.put("model", "configured-only");
        request.put("memoryModel", "memory-only");
        catalog.remember(request, Arrays.asList("custom-main", "custom-memory"));

        AgentModelCatalog reloaded = new AgentModelCatalog(file);
        JSONObject snapshot = reloaded.snapshot(request);
        String json = snapshot.getJSONArray("models").toJSONString();
        assertTrue(json.contains("gpt-live"));
        assertTrue(json.contains("gpt-old"));
        assertTrue(json.contains("legacy-model"));
        assertTrue(json.contains("custom-main"));
        assertTrue(json.contains("custom-memory"));
        assertTrue(json.contains("configured-only"));
        assertTrue(json.contains("memory-only"));
        assertEquals("acp", snapshot.getJSONArray("models").stream()
                .map(value -> (JSONObject) value)
                .filter(value -> "gpt-live".equals(value.getString("id")))
                .findFirst().get().getString("source"));
    }

    @Test
    public void sharesCodexLiveDiscoveryAcrossWorkspaces() throws Exception {
        Path file = temporaryFolder.newFolder("scope").toPath().resolve("models.json");
        AgentModelCatalog catalog = new AgentModelCatalog(file);
        JSONObject first = request();
        catalog.remember(first, Arrays.asList("custom-shared"));
        catalog.replaceLive(first,
                Arrays.asList(new AgentModelCatalog.ModelEntry("workspace-a", "Workspace A")),
                "api");

        JSONObject second = request();
        second.put("workDir", "/workspace/other");
        String json = catalog.snapshot(second).getJSONArray("models").toJSONString();
        assertTrue(json.contains("custom-shared"));
        assertTrue(json.contains("workspace-a"));
    }

    @Test
    public void keepsOpenCodeLiveDiscoveryScopedToWorkspace() throws Exception {
        Path file = temporaryFolder.newFolder("opencode-scope").toPath().resolve("models.json");
        AgentModelCatalog catalog = new AgentModelCatalog(file);
        JSONObject first = request();
        first.put("provider", "OPENCODE");
        catalog.remember(first, Arrays.asList("custom-shared"));
        catalog.replaceLive(first,
                Arrays.asList(new AgentModelCatalog.ModelEntry("workspace-a", "Workspace A")),
                "opencode");

        JSONObject second = request();
        second.put("provider", "OPENCODE");
        second.put("workDir", "/workspace/other");
        String json = catalog.snapshot(second).getJSONArray("models").toJSONString();
        assertTrue(json.contains("custom-shared"));
        assertTrue(!json.contains("workspace-a"));
    }

    @Test
    public void readsLegacyWorkspaceScopedCodexCatalogFromAnotherWorkspace() throws Exception {
        Path file = temporaryFolder.newFolder("legacy-codex").toPath().resolve("models.json");
        String legacy = "{\"histories\":{},\"catalogs\":{"
                + "\"CODEX_ACP|1.2.3|default|default|default|/workspace/old\":{"
                + "\"refreshedAt\":123,\"models\":[{\"id\":\"legacy-live\","
                + "\"name\":\"Legacy Live\",\"source\":\"acp\"}]}}}";
        Files.write(file, legacy.getBytes(StandardCharsets.UTF_8));

        AgentModelCatalog catalog = new AgentModelCatalog(file);
        JSONObject request = request();
        request.put("workDir", "/workspace/new");
        String json = catalog.snapshot(request).getJSONArray("models").toJSONString();
        assertTrue(json.contains("legacy-live"));
    }

    private JSONObject request() {
        JSONObject request = new JSONObject(true);
        request.put("provider", "CODEX_ACP");
        request.put("providerVersion", "1.2.3");
        request.put("workDir", "/workspace/demo");
        return request;
    }
}
