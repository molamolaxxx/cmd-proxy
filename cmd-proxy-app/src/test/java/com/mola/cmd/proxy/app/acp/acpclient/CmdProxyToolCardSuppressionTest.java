package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.JsonObject;
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
}
