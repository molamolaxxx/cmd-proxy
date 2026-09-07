package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.acpclient.agent.KiroCliAgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager;
import com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelTurnContext;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class AcpClientTaskTurnTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void activeTaskIdentityPreventsUnrelatedBusyTurnCancellation() throws Exception {
        AcpClient client = client();
        client.state.set(AbstractAcpClient.State.BUSY);
        PromptOptions task = PromptOptions.forTask(
                "task-1", "event-1", 3L, 4L, 2L, false);
        bindAccepted(client, task);

        assertTrue(client.isActiveTask("task-1"));
        assertFalse(client.isActiveTask("task-2"));
        assertSame(task, client.getActiveTaskOptions());

        bindAccepted(client, PromptOptions.defaults());
        assertFalse(client.isActiveTask("task-1"));
        assertNull(client.getActiveTaskOptions());
    }

    @Test public void matchingTaskCancellationUsesAcpSessionCancel() throws Exception {
        AcpClient client = client();
        client.state.set(AbstractAcpClient.State.BUSY);
        bindAccepted(client, PromptOptions.forTask(
                "task-1", "event-1", 3L, 4L, 2L, false));
        StringWriter protocol = new StringWriter();
        client.writer = new BufferedWriter(protocol);
        client.sessionId = "task-session";

        client.cancelForQueuedWork();

        assertTrue(protocol.toString().contains("\"method\":\"session/cancel\""));
        assertTrue(protocol.toString().contains("\"sessionId\":\"task-session\""));
    }

    @Test public void internalPromptOptionsAreNotClassifiedAsVisibleUserInput() {
        assertEquals(ContextMessage.UserOrigin.USER,
                AcpClient.historyOrigin(PromptOptions.defaults()));
        assertEquals(ContextMessage.UserOrigin.TASK,
                AcpClient.historyOrigin(PromptOptions.forTask(
                        "task-1", "event-1", 1L, 1L, 1L, false)));
        assertEquals(ContextMessage.UserOrigin.CHANNEL,
                AcpClient.historyOrigin(PromptOptions.forChannelReply(
                        new ChannelTurnContext("wecom", "reply", "group", "chat-1"))));
        assertEquals(ContextMessage.UserOrigin.TALK_TO,
                AcpClient.historyOrigin(PromptOptions.defaults().setInboundTalkTo(true)));
        assertEquals(ContextMessage.UserOrigin.SCHEDULE,
                AcpClient.historyOrigin(PromptOptions.forScheduleExecution()));
    }

    private AcpClient client() throws Exception {
        AcpClientIdentity identity = AcpClientIdentity.main(
                "task-group", "Task Agent", "Task Agent");
        ConversationHistoryManager history = new ConversationHistoryManager(
                identity, temporary.newFolder().toPath());
        return new AcpClient(new KiroCliAgentProvider(), ".", identity, null, history);
    }

    @SuppressWarnings("unchecked")
    private static void bindAccepted(AcpClient client, PromptOptions options) throws Exception {
        Field field = AcpClient.class.getDeclaredField("acceptedPromptOptions");
        field.setAccessible(true);
        ((AtomicReference<PromptOptions>) field.get(client)).set(options);
    }
}
