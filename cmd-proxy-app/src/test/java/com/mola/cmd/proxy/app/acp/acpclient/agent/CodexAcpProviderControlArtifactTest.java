package com.mola.cmd.proxy.app.acp.acpclient.agent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodexAcpProviderControlArtifactTest {

    @Test
    public void recognizesConversationInterruptedMarkerOnly() {
        CodexAcpProvider provider = new CodexAcpProvider();

        assertTrue(provider.isAgentMessageControlArtifact("*Conversation interrupted*"));
        assertTrue(provider.isAgentMessageControlArtifact("\n*Conversation interrupted*\n"));
        assertFalse(provider.isAgentMessageControlArtifact("Conversation interrupted"));
        assertFalse(provider.isAgentMessageControlArtifact("normal response"));
        assertFalse(provider.isAgentMessageControlArtifact(null));
    }
}
