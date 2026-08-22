package com.mola.cmd.proxy.app.acp.acpclient.agent;

import org.junit.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ClaudeAgentAcpProviderTest {

    @Test
    public void exposesClaudeGlobalAndProjectMcpConfigurationToAcpSession() {
        ClaudeAgentAcpProvider provider = new ClaudeAgentAcpProvider();

        List<Path> paths = provider.getMcpConfigPaths("/tmp/claude-workspace");

        assertEquals(2, paths.size());
        assertEquals(".claude.json", paths.get(0).getFileName().toString());
        assertEquals(".mcp.json", paths.get(1).getFileName().toString());
        assertEquals("/tmp/claude-workspace",
                paths.get(1).getParent().toString().replace('\\', '/'));
    }
}
