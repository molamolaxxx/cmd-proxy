package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CmdProxyToolCardSuppressionTest {
    @Test
    public void suppressesOnlyCmdProxyActionTools() {
        assertTrue(AcpClient.isCmdProxyActionToolCall(
                "mcp__cmd-proxy-runtime__talk_to", new JsonObject()));
        assertTrue(AcpClient.isCmdProxyActionToolCall(
                "cmd_proxy dispatch_subagent", new JsonObject()));
        assertFalse(AcpClient.isCmdProxyActionToolCall(
                "external talk_to", new JsonObject()));
        assertFalse(AcpClient.isCmdProxyActionToolCall(
                "mcp__cmd-proxy__read_file", new JsonObject()));
    }

    @Test
    public void usesDedicatedCardForContextCompactionLifecycle() {
        JsonObject update = new JsonObject();

        assertFalse(AcpClient.shouldPublishGenericToolCard(
                AgentProvider.CompactionSignal.STARTED,
                "Compact conversation", update));
        assertFalse(AcpClient.shouldPublishGenericToolCard(
                AgentProvider.CompactionSignal.COMPLETED,
                "Compact conversation", update));
        assertFalse(AcpClient.shouldPublishGenericToolCard(
                AgentProvider.CompactionSignal.FAILED,
                "Compact conversation", update));
        assertTrue(AcpClient.shouldPublishGenericToolCard(
                AgentProvider.CompactionSignal.NONE,
                "External tool", update));
    }
}
