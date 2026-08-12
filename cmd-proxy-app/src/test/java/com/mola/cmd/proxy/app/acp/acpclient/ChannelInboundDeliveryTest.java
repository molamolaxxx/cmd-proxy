package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.channel.ChannelTalkToMessage;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToMessage;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToRequest;
import org.junit.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

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
        assertTrue(client.lastPrompt.contains("\"action\":\"talk_to\""));
        assertTrue(client.lastPrompt.contains("\"target\":\"回复\""));
        assertTrue(client.lastPrompt.contains("同一逻辑 turn 可以回复多次"));
        assertFalse(client.lastPrompt.contains("reply_to_origin"));
        assertFalse(client.lastPrompt.contains("channel:wecom:r1"));
        assertNotNull(client.lastOptions.getChannelTurnContext());
        assertEquals("channel:wecom:r1",
                client.lastOptions.getChannelTurnContext().getReplyTarget());
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

    @Test
    public void localAttachmentsSurviveDirectAndQueuedInboundDelivery() {
        TalkToDispatcher dispatcher = dispatcher();
        FakeClient ready = new FakeClient("ready-group");
        ready.setReady();
        ChannelTalkToMessage direct = new ChannelTalkToMessage(
                "channel:wecom:r-direct", "wecom", "sender", "user-1",
                "group", "chat-1", "text", "inspect", null,
                Arrays.asList("/workspace/current.png"));

        assertEquals(TalkToDispatcher.InboundDeliveryResult.Status.DIRECT,
                dispatcher.deliverInbound("ready-group", ready, direct).getStatus());
        assertEquals(Arrays.asList("/workspace/current.png"), ready.lastLocalFiles);

        FakeClient busy = new FakeClient("busy-group");
        busy.setBusy();
        ChannelTalkToMessage queued = new ChannelTalkToMessage(
                "channel:wecom:r-queued", "wecom", "sender", "user-1",
                "group", "chat-1", "text", "inspect quote", null,
                Arrays.asList("/workspace/quote.png"));
        assertEquals(TalkToDispatcher.InboundDeliveryResult.Status.QUEUED,
                dispatcher.deliverInbound("busy-group", busy, queued).getStatus());
        assertEquals(Arrays.asList("/workspace/quote.png"),
                dispatcher.pollInbox("busy-group").getLocalAttachments());
    }

    @Test
    public void everyChannelTargetIsPinnedToCurrentChannelTurn() {
        FakeClient client = new FakeClient("bound-group");
        PromptOptions options = PromptOptions.forChannelReply(
                message("reply-token").getTurnContext());

        TalkToRequest reply = client.resolveChannelReplyTarget(
                new TalkToRequest("回复", "one", 0), options);
        TalkToRequest proactive = client.resolveChannelReplyTarget(
                new TalkToRequest("channel:another", "two", 0), options);
        TalkToRequest teammate = client.resolveChannelReplyTarget(
                new TalkToRequest("reviewer", "three", 0), options);

        assertEquals("channel:wecom:reply-token", reply.getTarget());
        assertEquals("channel:wecom:reply-token", proactive.getTarget());
        assertEquals("reviewer", teammate.getTarget());
        assertEquals(2, options.getChannelReplyAttempts());
    }

    @Test
    public void uniqueTalkToReturnRestoresOriginalChannelReplyContext() {
        FakeClient client = new FakeClient("bound-group");
        PromptOptions origin = PromptOptions.forChannelReply(
                message("reply-token").getTurnContext());
        client.recordPendingChannelReply(
                new TalkToRequest("reviewer", "please inspect", 0), origin,
                "[talkTo 结果]\n已成功将消息发送给 reviewer。");

        TalkToDispatcher.sendInboundMessage(client,
                new TalkToMessage("reviewer", "inspection complete", 1));

        assertNotNull(client.lastOptions);
        assertTrue(client.lastOptions.isRestoredChannelContinuation());
        assertEquals("channel:wecom:reply-token",
                client.lastOptions.getChannelTurnContext().getReplyTarget());
        assertTrue(client.lastPrompt.contains("[信道续接]"));
        assertTrue(client.lastPrompt.contains("target 指定为“回复”"));
        TalkToRequest resolved = client.resolveChannelReplyTarget(
                new TalkToRequest("回复", "final", 0), client.lastOptions);
        assertEquals("channel:wecom:reply-token", resolved.getTarget());
    }

    @Test
    public void unrelatedOrFailedTalkToDoesNotRestoreChannelContext() {
        FakeClient client = new FakeClient("bound-group");
        PromptOptions origin = PromptOptions.forChannelReply(
                message("reply-token").getTurnContext());
        client.recordPendingChannelReply(
                new TalkToRequest("reviewer", "please inspect", 0), origin,
                "[talkTo 结果]\n发送失败：reviewer 不可用。");

        assertFalse(client.promptOptionsForInboundTalkTo(
                new TalkToMessage("reviewer", "unexpected", 1))
                .hasChannelTurnContext());

        client.recordPendingChannelReply(
                new TalkToRequest("reviewer", "please inspect", 0), origin,
                "[talkTo 结果]\n已成功将消息发送给 reviewer。");
        assertFalse(client.promptOptionsForInboundTalkTo(
                new TalkToMessage("other", "not the expected sender", 1))
                .hasChannelTurnContext());
    }

    @Test
    public void crossChatterSenderPrefixRestoresOnlyUniqueDisplayNameOrigin() {
        FakeClient client = new FakeClient("bound-group");
        PromptOptions origin = PromptOptions.forChannelReply(
                message("reply-token").getTurnContext());
        client.recordPendingChannelReply(
                new TalkToRequest("reviewer", "please inspect", 0), origin,
                "[talkTo 结果]\n已成功将消息发送给 reviewer（跨服务器）。");

        PromptOptions restored = client.promptOptionsForInboundTalkTo(
                new TalkToMessage("remote-chatter:reviewer", "done", 1));

        assertTrue(restored.isRestoredChannelContinuation());
        assertEquals("channel:wecom:reply-token",
                restored.getChannelTurnContext().getReplyTarget());
    }

    @Test
    public void multipleOriginsForSameResponderFailClosedAsAmbiguous() {
        FakeClient client = new FakeClient("bound-group");
        PromptOptions first = PromptOptions.forChannelReply(
                message("first-route").getTurnContext());
        PromptOptions second = PromptOptions.forChannelReply(
                message("second-route").getTurnContext());
        TalkToRequest delegation = new TalkToRequest("reviewer", "inspect", 0);
        String accepted = "[talkTo 结果]\n已成功将消息发送给 reviewer。";
        client.recordPendingChannelReply(delegation, first, accepted);
        client.recordPendingChannelReply(delegation, second, accepted);

        PromptOptions restored = client.promptOptionsForInboundTalkTo(
                new TalkToMessage("reviewer", "which request?", 1));

        assertFalse(restored.hasChannelTurnContext());
    }

    @Test
    public void channelRouteIsSuspendedUntilExpectedReturnTurnCompletes() {
        FakeClient client = new FakeClient("bound-group");
        CountingDispatcher dispatcher = new CountingDispatcher();
        client.setTalkToSupport(dispatcher, null, Collections.emptyMap());
        PromptOptions origin = PromptOptions.forChannelReply(
                message("reply-token").getTurnContext());
        client.recordPendingChannelReply(
                new TalkToRequest("reviewer", "inspect", 0), origin,
                "[talkTo 结果]\n已成功将消息发送给 reviewer。");

        client.releaseChannelTurn(origin);
        assertEquals(0, dispatcher.releaseCount);

        PromptOptions restored = client.promptOptionsForInboundTalkTo(
                new TalkToMessage("reviewer", "done", 1));
        client.releaseChannelTurn(restored);

        assertEquals(1, dispatcher.releaseCount);
        assertEquals("channel:wecom:reply-token", dispatcher.lastReleasedTarget);
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
        private List<String> lastLocalFiles = Collections.emptyList();
        private PromptOptions lastOptions;

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

        @Override
        public void send(String userInput, List<Map<String, String>> files,
                         PromptOptions options) {
            this.lastPrompt = userInput;
            this.lastOptions = options;
            state.set(State.BUSY);
        }

        @Override
        public void sendLocalFiles(String userInput, List<String> localFiles) {
            this.lastPrompt = userInput;
            this.lastLocalFiles = localFiles;
            state.set(State.BUSY);
        }

        @Override
        public void sendLocalFiles(String userInput, List<String> localFiles,
                                   PromptOptions options) {
            this.lastPrompt = userInput;
            this.lastLocalFiles = localFiles;
            this.lastOptions = options;
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

    private static final class CountingDispatcher extends TalkToDispatcher {
        private int releaseCount;
        private String lastReleasedTarget;

        private CountingDispatcher() {
            super(Collections.emptyMap(), AcpClientRegistry.getInstance(),
                    Collections.emptyMap());
        }

        @Override
        public void releaseExternalTarget(String target) {
            releaseCount++;
            lastReleasedTarget = target;
        }
    }
}
