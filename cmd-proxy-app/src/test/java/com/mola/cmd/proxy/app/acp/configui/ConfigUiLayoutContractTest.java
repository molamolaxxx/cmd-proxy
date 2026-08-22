package com.mola.cmd.proxy.app.acp.configui;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;

public class ConfigUiLayoutContractTest {
    @Test
    public void separatesConfigurationIntoSearchablePaginatedPages() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("data-page=\"basic\""));
        assertTrue(html.contains("data-page=\"channels\""));
        assertTrue(html.contains("data-page=\"acp\""));
        assertTrue(html.contains("id=\"page-basic\""));
        assertTrue(html.contains("id=\"page-channels\""));
        assertTrue(html.contains("id=\"page-acp\""));

        assertTrue(html.contains("id=\"channelSearch\""));
        assertTrue(html.contains("id=\"robotSearch\""));
        assertTrue(html.contains("id=\"channelSearch\" type=\"text\""));
        assertTrue(html.contains("id=\"robotSearch\" type=\"text\""));
        assertTrue(html.contains("function channelSearchText"));
        assertTrue(html.contains("function robotSearchText"));
        assertTrue(html.contains("function relevanceScore"));
        assertTrue(html.contains("function matchGrade"));
        assertTrue(html.contains("function pathBaseName"));
        assertTrue(html.contains("{value:pathBaseName(r.workDir),weight:110}"));
        assertTrue(html.contains("{value:contacts,weight:2}"));
        assertTrue(html.contains("function filteredChannels"));
        assertTrue(html.contains("function filteredRobots"));
        assertTrue(html.contains("sort(function(a,b){return b.score-a.score||a.index-b.index})"));

        assertTrue(html.contains("id=\"channelPageSize\""));
        assertTrue(html.contains("id=\"robotPageSize\""));
        assertTrue(html.contains("function pageEntries"));
        assertTrue(html.contains("function renderPagination"));
        assertTrue(html.contains("entry.index"));
    }

    @Test
    public void keepsChannelSaveAndSingleRefreshActionsExplicitlySeparated() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("保存配置不会中断连接，刷新按钮仅应用当前信道"));
        assertTrue(html.contains("previousChannelId:previousId,channelId:channelId"));
        assertTrue(html.contains("/api/refresh-channel"));
    }

    @Test
    public void rendersRegisteredMcpToolsFromTheDocumentedStringArrayContract() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("function mcpToolName(tool){return typeof tool==='string'?tool:"));
        assertTrue(html.contains("tools.map(mcpToolName).filter(Boolean).join('、')"));
    }

    @Test
    public void memoryCanSwitchBetweenModelAndAnyConfiguredRobot() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("id=\"dMemExecutionMode\""));
        assertTrue(html.contains("value=\"model\""));
        assertTrue(html.contains("value=\"robot\""));
        assertTrue(html.contains("id=\"dMemRobot\""));
        assertTrue(html.contains("function memoryRobotOptions(selected)"));
        assertTrue(html.contains("robots.forEach(function(robot)"));
        assertTrue(html.contains("robot.enabled===false?'（已禁用）':''"));
        assertTrue(html.contains("executionMode:memExecutionMode,robotName:memRobotName||undefined"));
    }

    @Test
    public void exposesDeepSeekHarnessProviderWithSafeRuntimeControls() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("value=\"DEEPSEEK_HARNESS_ACP\""));
        assertTrue(html.contains("DeepSeek Harness (ACP·实验性)"));
        assertTrue(html.contains("id=\"dDeepSeekBaseUrl\""));
        assertTrue(html.contains("id=\"dDshHome\""));
        assertTrue(html.contains("id=\"dPermissionPolicy\""));
        assertTrue(html.contains("value=\"REJECT\""));
        assertTrue(html.contains("需要 Node.js 22+"));
        assertTrue(html.contains("permissionPolicy:provider==='DEEPSEEK_HARNESS_ACP'"));
    }

    private String loadConfigUi() throws Exception {
        InputStream input = getClass().getResourceAsStream("/configui/index.html");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
