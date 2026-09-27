package com.mola.cmd.proxy.app.acp.configui;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class AgentGatewayUiContractTest {
    @Test
    public void exposesGatewayTabTargetSelectionAndSecretControls() throws Exception {
        String html = load();
        assertTrue(html.contains(">智能体网关</button>"));
        assertTrue(html.contains("id=\"channelPanelGateway\""));
        assertTrue(html.contains("id=\"agentGatewayServerEnabled\""));
        assertTrue(html.contains("function openAgentGatewayDialog("));
        assertTrue(html.contains("function setAgentGatewayTarget("));
        assertTrue(html.contains("/api/agent-gateways/targets"));
        assertTrue(html.contains("/api/agent-gateways/status"));
        assertTrue(html.contains("/api/agent-gateways/auth-code?id="));
        assertTrue(html.contains("MolaChat 主智能体、Starweave 主智能体或本地 Team 成员"));
        assertTrue(html.contains("至少 32 个字符"));
        assertTrue(html.contains("允许下载附件的域名"));
    }

    private String load() throws Exception {
        return ConfigUiTestResources.loadBundle();
    }
}
