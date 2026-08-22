package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.acpclient.agent.KiroCliAgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AcpClientActionTurnStopTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void firstCapturedActionSendsSingleSessionCancel() throws Exception {
        AcpClientIdentity identity = AcpClientIdentity.main(
                "group-action-loop", "Action Robot", "Action Robot");
        ConversationHistoryManager history = new ConversationHistoryManager(
                identity, temporaryFolder.newFolder("history").toPath());
        AcpClient client = new AcpClient(new KiroCliAgentProvider(), ".", identity, null, history);
        StringWriter protocol = new StringWriter();
        client.writer = new BufferedWriter(protocol);
        client.sessionId = "session-action-loop";
        AtomicBoolean promptEnded = new AtomicBoolean(false);
        AtomicBoolean cancelRequested = new AtomicBoolean(false);

        client.requestActionTurnStop(promptEnded, cancelRequested);
        client.requestActionTurnStop(promptEnded, cancelRequested);

        String output = protocol.toString();
        assertEquals(1, occurrences(output, "\"method\":\"session/cancel\""));
        assertTrue(output.contains("\"sessionId\":\"session-action-loop\""));
        assertTrue(cancelRequested.get());
    }

    @Test
    public void lateCapturedActionDoesNotCancelCompletedPrompt() throws Exception {
        AcpClientIdentity identity = AcpClientIdentity.main(
                "group-late-action", "Late Robot", "Late Robot");
        ConversationHistoryManager history = new ConversationHistoryManager(
                identity, temporaryFolder.newFolder("late-history").toPath());
        AcpClient client = new AcpClient(new KiroCliAgentProvider(), ".", identity, null, history);
        StringWriter protocol = new StringWriter();
        client.writer = new BufferedWriter(protocol);
        client.sessionId = "session-late-action";
        AtomicBoolean cancelRequested = new AtomicBoolean(false);

        client.requestActionTurnStop(new AtomicBoolean(true), cancelRequested);

        assertTrue(protocol.toString().isEmpty());
        assertFalse(cancelRequested.get());
    }

    private static int occurrences(String text, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) >= 0) {
            count++;
            index += pattern.length();
        }
        return count;
    }
}
