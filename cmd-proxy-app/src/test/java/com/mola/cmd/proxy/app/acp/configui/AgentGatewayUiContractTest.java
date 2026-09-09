package com.mola.cmd.proxy.app.acp.configui;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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
        try (InputStream input = getClass().getResourceAsStream("/configui/index.html")) {
            if (input == null) throw new IllegalStateException("configui/index.html missing");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
