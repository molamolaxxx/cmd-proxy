package com.mola.cmd.proxy.app.acp.team.talkto;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToMessage;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToRequest;
import com.mola.cmd.proxy.app.acp.talkto.ExternalTalkToGateway;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import com.mola.cmd.proxy.app.acp.team.TeamClientRegistry;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventSink;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventType;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamContactRef;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class TeamTalkToDispatcherTest {

    @Test
    public void mixedRosterTargetEmitsRouteRequestInsteadOfUsingLocalRegistry() {
        TeamMemberDefinition local = member("member-1", 0)
                .withState(TeamMemberState.READY, "session-1", null);
        TeamDefinition creating = TeamDefinition.creating(
                "team-1", "owner-1", "Mixed", "team-acp-instance", "request-1",
                Collections.singletonList(local), true,
                Arrays.asList(TeamContactRef.from(local),
                        new TeamContactRef("member-remote", "team-acp-member-remote",
                                "Remote", "remote", 1)), 100L);
        TeamRuntime runtime = new TeamRuntime(creating.transitionWithMembers(
                TeamState.READY, Collections.singletonList(local), null, 101L));
        TeamClientRegistry registry = new TeamClientRegistry();
        registry.register("team-1", "member-1", client("member-1"));
        List<TeamEventEnvelope> events = new ArrayList<>();
        TeamTalkToDispatcher dispatcher = new TeamTalkToDispatcher(
                runtime, registry, events::add);

        String result = dispatcher.deliver(new TalkToRequest(
                "member-remote", "remote hello", 0), "member-1", "", null);

        assertTrue(result.contains("跨实例消息已提交路由"));
        assertEquals(Arrays.asList(
                        TeamEventType.TALK_TO_ROUTE_REQUEST,
                        TeamEventType.TALK_TO_SEND,
                        TeamEventType.MESSAGE_CHUNK),
                types(events));
        TeamEventEnvelope route = lastEvent(events, TeamEventType.TALK_TO_ROUTE_REQUEST);
        assertNotNull(route);
        assertEquals("member-remote", data(route).get("targetTeamMemberId"));
        assertEquals("ROUTE_REQUESTED", data(route).get("delivery"));
        Map<String, Object> card = data(events.get(2));
        assertEquals("TEAM_TALK_TO", card.get("cardType"));
        assertEquals("SEND", card.get("direction"));
        assertEquals("member-remote", card.get("cardTargetTeamMemberId"));
        assertEquals("ROUTE_REQUESTED", card.get("delivery"));
        assertTrue(card.get("content").toString().contains("remote hello"));
        assertTrue(card.get("content").toString().contains("Remote"));
    }

    @Test
    public void mixedRemoteDirectDeliveryPublishesReceiveCard() {
        Fixture fixture = mixedInboundFixture(false);

        TalkToDispatcher.InboundDeliveryResult result =
                fixture.dispatcher.deliverRemoteInbound(
                        "message-1", "member-remote", "member-2",
                        "remote hello", 1);

        assertEquals(TalkToDispatcher.InboundDeliveryResult.Status.DIRECT,
                result.getStatus());
        assertNotNull(fixture.target.lastPrompt);
        assertTrue(fixture.target.lastPrompt.contains("remote hello"));
        assertEquals(Arrays.asList(
                        TeamEventType.TALK_TO_RECEIVE,
                        TeamEventType.MESSAGE_CHUNK),
                types(fixture.events));
        Map<String, Object> card = data(fixture.events.get(1));
        assertEquals("TEAM_TALK_TO", card.get("cardType"));
        assertEquals("RECEIVE", card.get("direction"));
        assertEquals("member-remote", card.get("cardTargetTeamMemberId"));
        assertEquals("DIRECT", card.get("delivery"));
        assertTrue(card.get("content").toString().contains("Remote"));
    }

    @Test
    public void mixedRemoteQueuedDeliveryPublishesReceiveCardWhenDrained() {
        Fixture fixture = mixedInboundFixture(true);

        TalkToDispatcher.InboundDeliveryResult result =
                fixture.dispatcher.deliverRemoteInbound(
                        "message-queued", "member-remote", "member-2",
                        "queued remote hello", 1);

        assertEquals(TalkToDispatcher.InboundDeliveryResult.Status.QUEUED,
                result.getStatus());
        assertEquals(Collections.singletonList(TeamEventType.TALK_TO_QUEUED),
                types(fixture.events));

        TalkToMessage drained = fixture.dispatcher.pollInbox("member-2");

        assertNotNull(drained);
        assertEquals("queued remote hello", drained.getContent());
        assertEquals(Arrays.asList(
                        TeamEventType.TALK_TO_QUEUED,
                        TeamEventType.TALK_TO_RECEIVE,
                        TeamEventType.MESSAGE_CHUNK),
                types(fixture.events));
        Map<String, Object> card = data(fixture.events.get(2));
        assertEquals("TEAM_TALK_TO", card.get("cardType"));
        assertEquals("RECEIVE", card.get("direction"));
        assertEquals("member-remote", card.get("cardTargetTeamMemberId"));
        assertEquals("DELIVERED_FROM_INBOX", card.get("delivery"));
    }

    @Test
    public void mixedRouteAdmissionFailureIsExplicitAndRetryable() {
        TeamMemberDefinition local = member("member-1", 0)
                .withState(TeamMemberState.READY, "session-1", null);
        TeamDefinition creating = TeamDefinition.creating(
                "team-1", "owner-1", "Mixed", "team-acp-instance", "request-1",
                Collections.singletonList(local), true,
                Arrays.asList(TeamContactRef.from(local),
                        new TeamContactRef("member-remote", "team-acp-member-remote",
                                "Remote", "remote", 1)), 100L);
        TeamRuntime runtime = new TeamRuntime(creating.transitionWithMembers(
                TeamState.READY, Collections.singletonList(local), null, 101L));
        TeamClientRegistry registry = new TeamClientRegistry();
        registry.register("team-1", "member-1", client("member-1"));
        AtomicInteger attempts = new AtomicInteger();
        TeamEventSink rejectingSink = new TeamEventSink() {
            @Override
            public void publish(TeamEventEnvelope event) {
            }

            @Override
            public boolean tryPublish(TeamEventEnvelope event) {
                attempts.incrementAndGet();
                return false;
            }
        };
        TeamTalkToDispatcher dispatcher = new TeamTalkToDispatcher(
                runtime, registry, rejectingSink);

        String first = dispatcher.deliver(new TalkToRequest(
                "member-remote", "remote hello", 0), "member-1", "", null);
        String retry = dispatcher.deliver(new TalkToRequest(
                "member-remote", "remote hello", 0), "member-1", "", null);

        assertTrue(first.contains("发送失败"));
        assertTrue(first.contains("消息未提交"));
        assertFalse(first.contains("已提交路由"));
        assertTrue(retry.contains("发送失败"));
        assertEquals(2, attempts.get());
    }

    @Test
    public void routesDirectlyByMemberIdAndPublishesSendReceive() {
        Fixture fixture = fixture();

        String result = fixture.dispatcher.deliver(
                new TalkToRequest("member-2", "hello", 0),
                "member-1", "ignored-owner", null);

        assertTrue(result.contains("已成功"));
        assertNotNull(fixture.target.lastPrompt);
        assertTrue(fixture.target.lastPrompt.contains("hello"));
        assertTrue(fixture.target.lastPrompt.contains("target 精确设置为：member-1"));
        assertTrue(fixture.target.lastPrompt.contains("_depth 设置为：1"));
        assertFalse(fixture.target.lastPrompt.contains("\"action\""));
        assertEquals(Arrays.asList(
                        TeamEventType.TALK_TO_SEND,
                        TeamEventType.MESSAGE_CHUNK,
                        TeamEventType.TALK_TO_RECEIVE,
                        TeamEventType.MESSAGE_CHUNK),
                types(fixture.events));
        Map<?, ?> send = data(fixture.events.get(0));
        assertEquals("member-1", send.get("senderTeamMemberId"));
        assertEquals("member-2", send.get("targetTeamMemberId"));
        assertEquals("DELIVERED", send.get("delivery"));
        Map<String, Object> senderCard = data(fixture.events.get(1));
        assertEquals("TEAM_TALK_TO", senderCard.get("cardType"));
        assertEquals("member-2", senderCard.get("cardTargetTeamMemberId"));
        assertEquals("SEND", senderCard.get("direction"));
        assertTrue(senderCard.get("content").toString().contains(
                "data-team-member-id=\"member-2\""));
        assertTrue(senderCard.get("content").toString().contains(
                "路由 target：`member-2`"));
        assertFalse(senderCard.get("content").toString().contains(
                "team-acp-member-2"));
        assertFalse(senderCard.get("content").toString().contains(
                "source-robot-member-2"));
        Map<String, Object> receiverCard = data(fixture.events.get(3));
        assertEquals("member-1", receiverCard.get("cardTargetTeamMemberId"));
        assertEquals("RECEIVE", receiverCard.get("direction"));
    }

    @Test
    public void busyTargetUsesBoundedFifoInboxAndDeliversAfterReady() {
        Fixture fixture = fixture();
        fixture.target.setClientState(AbstractAcpClient.State.BUSY);
        setMemberState(fixture.runtime, "member-2", TeamMemberState.BUSY);

        for (int i = 0; i < TeamTalkToDispatcher.INBOX_CAPACITY; i++) {
            String result = fixture.dispatcher.deliver(
                    new TalkToRequest("member-2", "queued-" + i, 0),
                    "member-1", "", null);
            assertTrue(result.contains("Team inbox"));
        }
        String full = fixture.dispatcher.deliver(
                new TalkToRequest("member-2", "overflow", 0),
                "member-1", "", null);

        assertEquals(10, fixture.dispatcher.inboxSize("member-2"));
        assertTrue(full.contains("inbox 已满"));
        assertNotNull(lastEvent(fixture.events, TeamEventType.TALK_TO_REJECTED));

        TalkToMessage first = fixture.dispatcher.pollInbox("member-2");
        assertNotNull(first);
        assertEquals("queued-0", first.getContent());
        assertEquals(9, fixture.dispatcher.inboxSize("member-2"));
        TeamEventEnvelope receive = lastEvent(
                fixture.events, TeamEventType.TALK_TO_RECEIVE);
        assertNotNull(receive);
        assertEquals(TeamEventType.TALK_TO_RECEIVE, receive.getType());
        assertEquals("DELIVERED_FROM_INBOX", data(receive).get("delivery"));
        TeamEventEnvelope receiveCard =
                fixture.events.get(fixture.events.size() - 1);
        assertEquals(TeamEventType.MESSAGE_CHUNK, receiveCard.getType());
        assertEquals("member-1",
                data(receiveCard).get("cardTargetTeamMemberId"));
    }

    @Test
    public void expiresInboxEntriesAndEmitsRejectedForOriginalSender() {
        AtomicLong clock = new AtomicLong(100L);
        Fixture fixture = fixture(clock, 50L, 60_000L);
        fixture.target.setClientState(AbstractAcpClient.State.BUSY);
        setMemberState(fixture.runtime, "member-2", TeamMemberState.BUSY);
        fixture.dispatcher.deliver(new TalkToRequest(
                "member-2", "expires", 0), "member-1", "", null);

        clock.set(151L);

        assertNull(fixture.dispatcher.pollInbox("member-2"));
        TeamEventEnvelope expired = lastEvent(
                fixture.events, TeamEventType.TALK_TO_REJECTED);
        assertNotNull(expired);
        assertEquals(TeamEventType.TALK_TO_REJECTED, expired.getType());
        assertEquals("member-1", expired.getTeamMemberId());
        assertEquals("TTL_EXPIRED", data(expired).get("reason"));
    }

    @Test
    public void rejectsCrossTeamOrdinarySelfDuplicateAndDepthRoutes() {
        Fixture fixture = fixture();

        assertTrue(fixture.dispatcher.deliver(new TalkToRequest(
                "source-robot-member-2", "x", 0), "member-1", "", null)
                .contains("teamMemberId"));
        assertTrue(fixture.dispatcher.deliver(new TalkToRequest(
                "owner-2:member-2", "x", 0), "member-1", "", null)
                .contains("teamMemberId"));
        assertTrue(fixture.dispatcher.deliver(new TalkToRequest(
                "Display member-2", "x", 0), "member-1", "", null)
                .contains("teamMemberId"));
        assertTrue(fixture.dispatcher.deliver(new TalkToRequest(
                "team-acp-member-2", "x", 0), "member-1", "", null)
                .contains("teamMemberId"));
        assertTrue(fixture.dispatcher.deliver(new TalkToRequest(
                "member-1", "x", 0), "member-1", "", null)
                .contains("不能向自己"));
        assertFalse(types(fixture.events).contains(TeamEventType.MESSAGE_CHUNK));

        assertTrue(fixture.dispatcher.deliver(new TalkToRequest(
                "member-2", "same", 0), "member-1", "", null)
                .contains("已成功"));
        assertTrue(fixture.dispatcher.deliver(new TalkToRequest(
                "member-2", "same", 0), "member-1", "", null)
                .contains("相同消息"));
        assertTrue(fixture.dispatcher.deliver(new TalkToRequest(
                "member-2", "deep", TeamTalkToDispatcher.MAX_DEPTH),
                "member-1", "", null).contains("深度"));

        assertTrue(types(fixture.events).contains(TeamEventType.TALK_TO_REJECTED));
        assertEquals(1, fixture.target.sendCount);
    }

    @Test
    public void cleanupDropsInboxAndDedupWithoutTouchingClientRegistry() {
        Fixture fixture = fixture();
        fixture.target.setClientState(AbstractAcpClient.State.BUSY);
        setMemberState(fixture.runtime, "member-2", TeamMemberState.BUSY);
        fixture.dispatcher.deliver(new TalkToRequest(
                "member-2", "queued", 0), "member-1", "", null);

        fixture.dispatcher.close();

        assertEquals(0, fixture.dispatcher.inboxSize("member-2"));
        assertEquals(0, fixture.dispatcher.dedupSize());
        assertEquals(2, fixture.registry.size());
        assertTrue(fixture.dispatcher.deliver(new TalkToRequest(
                "member-2", "after-close", 0), "member-1", "", null)
                .contains("not accepting"));
    }

    @Test
    public void concurrentBusyOffersNeverExceedPerMemberCapacity() throws Exception {
        Fixture fixture = fixture();
        fixture.target.setClientState(AbstractAcpClient.State.BUSY);
        setMemberState(fixture.runtime, "member-2", TeamMemberState.BUSY);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(20);
        AtomicInteger queued = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            for (int i = 0; i < 20; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        start.await();
                        String result = fixture.dispatcher.deliver(
                                new TalkToRequest("member-2",
                                        "concurrent-" + index, 0),
                                "member-1", "", null);
                        if (result.contains("已放入其 Team inbox")) {
                            queued.incrementAndGet();
                        } else {
                            rejected.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(TeamTalkToDispatcher.INBOX_CAPACITY, queued.get());
        assertEquals(10, rejected.get());
        assertEquals(TeamTalkToDispatcher.INBOX_CAPACITY,
                fixture.dispatcher.inboxSize("member-2"));
    }

    @Test
    public void productionFiveArgumentDispatchKeepsStrictTeamRouting() {
        Fixture fixture = fixture();

        String result = fixture.dispatcher.deliver(
                new TalkToRequest("member-2", "five-args", 0),
                "member-1", "owner-1", "team-acp-member-1", null);

        assertTrue(result.contains("已成功"));
        assertTrue(fixture.target.lastPrompt.contains("five-args"));
    }

    @Test
    public void externalChannelMessageQueuesInAndDrainsFromMemberInbox() {
        Fixture fixture = fixture();
        fixture.target.setClientState(AbstractAcpClient.State.BUSY);
        setMemberState(fixture.runtime, "member-2", TeamMemberState.BUSY);
        TalkToMessage message = new TalkToMessage("channel:wecom:r_one", "external", 1);

        TalkToDispatcher.InboundDeliveryResult result = fixture.dispatcher.deliverInbound(
                "member-2", fixture.target, message);
        TalkToMessage drained = fixture.dispatcher.pollInbox("member-2");

        assertEquals(TalkToDispatcher.InboundDeliveryResult.Status.QUEUED, result.getStatus());
        assertNotNull(drained);
        assertEquals("channel:wecom:r_one", drained.getSender());
    }

    @Test
    public void channelReplyUsesStableTeamMemberOwnerKey() {
        Fixture fixture = fixture();
        final String[] senderKey = new String[1];
        fixture.dispatcher.registerExternalGateway(new ExternalTalkToGateway() {
            @Override public boolean supports(String target) {
                return target.startsWith("channel:");
            }
            @Override public String deliver(TalkToRequest request, String senderName,
                                            String senderGroupId) {
                senderKey[0] = senderGroupId;
                return "sent";
            }
        });

        String result = fixture.dispatcher.deliver(
                new TalkToRequest("channel:wecom:r_one", "reply", 0),
                "member-2", "owner-1", "ignored-logical-id", null);

        assertEquals("sent", result);
        assertEquals("team:team-1:member-2", senderKey[0]);
    }

    private static Fixture fixture() {
        return fixture(new AtomicLong(100L),
                TeamTalkToDispatcher.INBOX_TTL_MS,
                TeamTalkToDispatcher.DEDUP_WINDOW_MS);
    }

    private static Fixture fixture(AtomicLong clock, long ttl, long dedup) {
        TeamMemberDefinition first = member("member-1", 0)
                .withState(TeamMemberState.READY, "session-1", null);
        TeamMemberDefinition second = member("member-2", 1)
                .withState(TeamMemberState.READY, "session-2", null);
        TeamDefinition creating = TeamDefinition.creating(
                "team-1", "owner-1", "Fast Team", "team-acp-instance", "request-1",
                Arrays.asList(first, second), 100L);
        TeamDefinition ready = creating.transitionWithMembers(
                TeamState.READY, Arrays.asList(first, second), null, 101L);
        TeamRuntime runtime = new TeamRuntime(ready);
        TeamClientRegistry registry = new TeamClientRegistry();
        TestClient sender = client("member-1");
        TestClient target = client("member-2");
        registry.register("team-1", "member-1", sender);
        registry.register("team-1", "member-2", target);
        List<TeamEventEnvelope> events =
                Collections.synchronizedList(new ArrayList<>());
        TeamTalkToDispatcher dispatcher = new TeamTalkToDispatcher(
                runtime, registry, events::add, clock::get, ttl, dedup);
        return new Fixture(runtime, registry, dispatcher, target, events);
    }

    private static Fixture mixedInboundFixture(boolean busy) {
        TeamMemberDefinition target = member("member-2", 0).withState(
                busy ? TeamMemberState.BUSY : TeamMemberState.READY,
                "session-2", null);
        TeamContactRef remote = new TeamContactRef(
                "member-remote", "team-acp-member-remote",
                "Remote", "remote", 1);
        TeamDefinition creating = TeamDefinition.creating(
                "team-1", "owner-1", "Mixed", "team-acp-instance", "request-1",
                Collections.singletonList(target), true,
                Arrays.asList(TeamContactRef.from(target), remote), 100L);
        TeamRuntime runtime = new TeamRuntime(creating.transitionWithMembers(
                TeamState.READY, Collections.singletonList(target), null, 101L));
        TeamClientRegistry registry = new TeamClientRegistry();
        TestClient targetClient = client("member-2");
        if (busy) targetClient.setClientState(AbstractAcpClient.State.BUSY);
        registry.register("team-1", "member-2", targetClient);
        List<TeamEventEnvelope> events = new ArrayList<>();
        TeamTalkToDispatcher dispatcher = new TeamTalkToDispatcher(
                runtime, registry, events::add);
        return new Fixture(runtime, registry, dispatcher, targetClient, events);
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

    private static TestClient client(String memberId) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("source-robot-" + memberId);
        AcpClientIdentity identity = AcpClientIdentity.team(
                "team-acp-" + memberId, "team-acp-instance",
                "team/team-1/" + memberId, "owner-1",
                "team-1", memberId, robot.getName());
        return new TestClient(identity, robot);
    }

    private static TeamMemberDefinition member(String id, int order) {
        return new TeamMemberDefinition(
                id, "acp-source-" + id, "source-group-" + id,
                "source-robot-" + id, "Display " + id, "", order,
                "remark " + id, "fingerprint-" + id);
    }

    private static List<TeamEventType> types(List<TeamEventEnvelope> events) {
        List<TeamEventType> result = new ArrayList<>();
        for (TeamEventEnvelope event : events) {
            result.add(event.getType());
        }
        return result;
    }

    private static TeamEventEnvelope lastEvent(
            List<TeamEventEnvelope> events, TeamEventType type) {
        for (int index = events.size() - 1; index >= 0; index--) {
            if (events.get(index).getType() == type) return events.get(index);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(TeamEventEnvelope event) {
        return (Map<String, Object>) event.getData();
    }

    private static final class TestClient extends AcpClient {
        private String lastPrompt;
        private int sendCount;

        private TestClient(AcpClientIdentity identity, AcpRobotParam robot) {
            super(".", identity, robot);
            state.set(AbstractAcpClient.State.READY);
            setSessionId("session-" + identity.getTeamMemberId());
        }

        @Override
        public void send(String input, List<Map<String, String>> files) {
            lastPrompt = input;
            sendCount++;
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
        private final TeamClientRegistry registry;
        private final TeamTalkToDispatcher dispatcher;
        private final TestClient target;
        private final List<TeamEventEnvelope> events;

        private Fixture(TeamRuntime runtime, TeamClientRegistry registry,
                        TeamTalkToDispatcher dispatcher, TestClient target,
                        List<TeamEventEnvelope> events) {
            this.runtime = runtime;
            this.registry = registry;
            this.dispatcher = dispatcher;
            this.target = target;
            this.events = events;
        }
    }
}
