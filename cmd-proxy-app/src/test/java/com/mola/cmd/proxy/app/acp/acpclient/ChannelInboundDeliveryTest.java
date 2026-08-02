package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.channel.ChannelTalkToMessage;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToMessage;
import org.junit.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class ChannelInboundDeliveryTest {

    @Test
    public void readyClientReceivesImmediatelyWithoutChangingItsSession() {
        FakeClient client = new FakeClient("bound-group");
        client.setReady();
        TalkToDispatcher dispatcher = dispatcher();
        String sessionBefore = client.getSessionId();

        TalkToDispatcher.InboundDeliveryResult result = dispatcher.deliverInbound(
                "bound-group", client, message("r1"));

        assertEquals(TalkToDispatcher.InboundDeliveryResult.Status.DIRECT, result.getStatus());
        assertNotNull(client.lastPrompt);
        assertTrue(client.lastPrompt.contains("channel:wecom:r1"));
        assertEquals(sessionBefore, client.getSessionId());
    }

    @Test
    public void busyClientUsesExactGroupQueueAndRejectsSixthMessage() {
        FakeClient client = new FakeClient("bound-group");
        client.setBusy();
        TalkToDispatcher dispatcher = dispatcher();

        for (int i = 1; i <= 5; i++) {
            TalkToDispatcher.InboundDeliveryResult result = dispatcher.deliverInbound(
                    "bound-group", client, message("r" + i));
            assertEquals(TalkToDispatcher.InboundDeliveryResult.Status.QUEUED, result.getStatus());
            assertEquals(i, result.getQueuePosition());
        }
        TalkToDispatcher.InboundDeliveryResult sixth = dispatcher.deliverInbound(
                "bound-group", client, message("r6"));

        assertEquals(TalkToDispatcher.InboundDeliveryResult.Status.REJECTED, sixth.getStatus());
        TalkToMessage first = dispatcher.pollInbox("same-robot", "bound-group");
        assertNotNull(first);
        assertEquals("channel:wecom:r1", first.getSender());
        assertNull(dispatcher.pollInbox("another-group"));
    }

    private static ChannelTalkToMessage message(String route) {
        return new ChannelTalkToMessage("channel:wecom:" + route,
                "企业微信", "sender", "user-1", "group", "chat-1", "hello");
    }

    private static TalkToDispatcher dispatcher() {
        return new TalkToDispatcher(Collections.emptyMap(), AcpClientRegistry.getInstance(),
                Collections.emptyMap());
    }

    private static final class FakeClient extends AcpClient {
        private String lastPrompt;

        private FakeClient(String groupId) {
            super(new FakeProvider(), System.getProperty("java.io.tmpdir"), groupId, robot());
            setSessionId("existing-session");
        }

        private static AcpRobotParam robot() {
            AcpRobotParam robot = new AcpRobotParam();
            robot.setName("same-robot");
            return robot;
        }

        private void setReady() { state.set(State.READY); }
        private void setBusy() { state.set(State.BUSY); }

        @Override
        public void send(String userInput, List<Map<String, String>> files) {
            this.lastPrompt = userInput;
            state.set(State.BUSY);
        }
    }

    private static final class FakeProvider implements AgentProvider {
        @Override public String getCommand() { return "unused"; }
        @Override public String[] getArgs() { return new String[0]; }
        @Override public List<Path> getMcpConfigPaths(String workspacePath) {
            return Collections.emptyList();
        }
        @Override public String getName() { return "fake"; }
        @Override public double extractContextUsage(JsonObject msg) { return -1; }
    }
}
