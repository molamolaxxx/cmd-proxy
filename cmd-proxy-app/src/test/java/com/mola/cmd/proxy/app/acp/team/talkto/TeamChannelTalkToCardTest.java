package com.mola.cmd.proxy.app.acp.team.talkto;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.channel.ChannelTalkToMessage;
import com.mola.cmd.proxy.app.acp.talkto.ExternalTalkToGateway;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToMessage;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToRequest;
import com.mola.cmd.proxy.app.acp.team.TeamClientRegistry;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventType;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberState;
import com.mola.cmd.proxy.app.acp.team.model.TeamState;
import com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TeamChannelTalkToCardTest {

    @Test
    public void directChannelInboundPublishesReceiveCardForBoundMember() {
        Fixture fixture = fixture();
        ChannelTalkToMessage message = new ChannelTalkToMessage(
                "channel:wecom-main:r_secret", "wecom-main", "张三", "zhangsan",
                "group", "chat-1", "请检查构建");

        TalkToDispatcher.InboundDeliveryResult result = fixture.dispatcher.deliverInbound(
                "member-2", fixture.client, message);

        assertEquals(TalkToDispatcher.InboundDeliveryResult.Status.DIRECT,
                result.getStatus());
        assertEquals(Arrays.asList(
                TeamEventType.TALK_TO_RECEIVE, TeamEventType.MESSAGE_CHUNK),
                eventTypes(fixture.events));
        assertChannelCard(fixture.events.get(1), "RECEIVE", "channel:wecom-main",
                "请检查构建");
        assertFalse(cardContent(fixture.events.get(1)).contains("r_secret"));
    }

    @Test
    public void channelReplyPublishesSendCardForSendingMember() {
        Fixture fixture = fixture();
        final String[] ownerKey = new String[1];
        fixture.dispatcher.registerExternalGateway(new ExternalTalkToGateway() {
            @Override
            public boolean supports(String target) {
                return target.startsWith("channel:");
            }

            @Override
            public String deliver(TalkToRequest request, String senderName,
                                  String senderGroupId) {
                ownerKey[0] = senderGroupId;
                return "[talkTo 结果]\n已通过外部信道发送最终回复。";
            }
        });

        String result = fixture.dispatcher.deliver(
                new TalkToRequest("channel:wecom-main:r_secret", "构建已完成", 0),
                "member-2", "owner-1", "ignored-logical-id", Collections.emptyList());

        assertTrue(result.contains("已通过外部信道"));
        assertEquals("team:team-1:member-2", ownerKey[0]);
        assertEquals(Arrays.asList(
                TeamEventType.TALK_TO_SEND, TeamEventType.MESSAGE_CHUNK),
                eventTypes(fixture.events));
        assertChannelCard(fixture.events.get(1), "SEND", "channel:wecom-main",
                "构建已完成");
        assertFalse(cardContent(fixture.events.get(1)).contains("r_secret"));
    }

    @Test
    public void queuedChannelInboundPublishesCardWhenMemberDrainsInbox() {
        Fixture fixture = fixture();
        fixture.client.setClientState(AbstractAcpClient.State.BUSY);
        setMemberState(fixture.runtime, "member-2", TeamMemberState.BUSY);
        ChannelTalkToMessage message = new ChannelTalkToMessage(
                "channel:wecom-main:r_secret", "wecom-main", "张三", "zhangsan",
                "group", "chat-1", "排队消息");

        TalkToDispatcher.InboundDeliveryResult result = fixture.dispatcher.deliverInbound(
                "member-2", fixture.client, message);
        TalkToMessage drained = fixture.dispatcher.pollInbox("member-2");
        fixture.dispatcher.pushIncomingMessageCard(fixture.client, drained);

        assertEquals(TalkToDispatcher.InboundDeliveryResult.Status.QUEUED,
                result.getStatus());
        assertEquals(Arrays.asList(
                TeamEventType.TALK_TO_RECEIVE, TeamEventType.MESSAGE_CHUNK),
                eventTypes(fixture.events));
        assertChannelCard(fixture.events.get(1), "RECEIVE", "channel:wecom-main",
                "排队消息");
    }

    private static void assertChannelCard(TeamEventEnvelope event, String direction,
                                          String target, String content) {
        Map<String, Object> data = data(event);
        assertEquals("CHANNEL_TALK_TO", data.get("cardType"));
        assertEquals(direction, data.get("direction"));
        assertEquals(target, data.get("channelTarget"));
        assertTrue(String.valueOf(data.get("content")).contains(content));
        assertTrue(String.valueOf(data.get("content")).contains(target));
    }

    private static String cardContent(TeamEventEnvelope event) {
        return String.valueOf(data(event).get("content"));
    }

    private static List<TeamEventType> eventTypes(List<TeamEventEnvelope> events) {
        List<TeamEventType> types = new ArrayList<>();
        for (TeamEventEnvelope event : events) types.add(event.getType());
        return types;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(TeamEventEnvelope event) {
        return (Map<String, Object>) event.getData();
    }

    private static Fixture fixture() {
        TeamMemberDefinition first = member("member-1", 0)
                .withState(TeamMemberState.READY, "session-1", null);
        TeamMemberDefinition second = member("member-2", 1)
                .withState(TeamMemberState.READY, "session-2", null);
        TeamDefinition creating = TeamDefinition.creating(
                "team-1", "owner-1", "Fast Team", "team-acp-instance", "request-1",
                Arrays.asList(first, second), 100L);
        TeamRuntime runtime = new TeamRuntime(creating.transitionWithMembers(
                TeamState.READY, Arrays.asList(first, second), null, 101L));
        TeamClientRegistry registry = new TeamClientRegistry();
        TestClient client = client("member-2");
        registry.register("team-1", "member-2", client);
        List<TeamEventEnvelope> events = new ArrayList<>();
        TeamTalkToDispatcher dispatcher = new TeamTalkToDispatcher(
                runtime, registry, events::add);
        return new Fixture(runtime, dispatcher, client, events);
    }

    private static void setMemberState(TeamRuntime runtime, String memberId,
                                       TeamMemberState state) {
        List<TeamMemberDefinition> members = new ArrayList<>();
        for (TeamMemberDefinition member : runtime.getDefinition().getMembers()) {
            members.add(member.getTeamMemberId().equals(memberId)
                    ? member.withState(state, member.getSessionId(), null) : member);
        }
        assertTrue(runtime.publishNextDefinition(
                runtime.getDefinition().withMembers(members, 200L)));
    }

    private static TeamMemberDefinition member(String id, int order) {
        return new TeamMemberDefinition(
                id, "acp-source-" + id, "source-group-" + id,
                "source-robot-" + id, "Display " + id, "", order,
                "remark " + id, "fingerprint-" + id);
    }

    private static TestClient client(String memberId) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("source-robot-" + memberId);
        AcpClientIdentity identity = AcpClientIdentity.team(
                "team-acp-" + memberId, "team-acp-instance",
                "team/team-1/" + memberId, "owner-1",
                "team-1", memberId, robot.getName());
        return new TestClient(identity, robot);
    }

    private static final class TestClient extends AcpClient {
        private TestClient(AcpClientIdentity identity, AcpRobotParam robot) {
            super(".", identity, robot);
            state.set(AbstractAcpClient.State.READY);
            setSessionId("session-" + identity.getTeamMemberId());
        }

        @Override
        public void send(String input, List<Map<String, String>> files) {
        }

        private void setClientState(AbstractAcpClient.State next) {
            state.set(next);
        }

        @Override
        public void close() throws IOException {
            state.set(AbstractAcpClient.State.CLOSED);
        }
    }

    private static final class Fixture {
        private final TeamRuntime runtime;
        private final TeamTalkToDispatcher dispatcher;
        private final TestClient client;
        private final List<TeamEventEnvelope> events;

        private Fixture(TeamRuntime runtime, TeamTalkToDispatcher dispatcher, TestClient client,
                        List<TeamEventEnvelope> events) {
            this.runtime = runtime;
            this.dispatcher = dispatcher;
            this.client = client;
            this.events = events;
        }
    }
}
