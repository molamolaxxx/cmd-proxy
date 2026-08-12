package com.mola.cmd.proxy.app.acp.team.event;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime;
import com.mola.cmd.proxy.client.resp.CmdResponseContent;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class TeamEventContractTest {

    @Test
    public void eventSequenceIsMonotonicWithinTeamAndCarriesVersion() {
        TeamRuntime runtime = new TeamRuntime(definition());

        TeamEventEnvelope first = TeamEventEnvelope.next(runtime, null, null,
                TeamEventType.TEAM_CREATE_ACCEPTED, Collections.singletonMap("ok", true));
        TeamEventEnvelope second = TeamEventEnvelope.next(runtime, "member-1",
                "team-acp-member-1", TeamEventType.MESSAGE_CHUNK,
                Collections.singletonMap("content", "hello"));

        assertEquals(1L, first.getEventSeq());
        assertEquals(2L, second.getEventSeq());
        assertEquals(1L, second.getTeamVersion());
        assertNotEquals(first.getEventId(), second.getEventId());
    }

    @Test
    public void callbackResultMapUsesOnlyStringValuesAndJsonData() {
        TeamRuntime runtime = new TeamRuntime(definition());
        TeamEventEnvelope event = TeamEventEnvelope.next(runtime, "member-1",
                "team-acp-member-1", TeamEventType.MESSAGE_CHUNK,
                Collections.singletonMap("content", "hello"));

        Map<String, String> result = TeamEventCodec.toResultMap(event);
        JsonObject data = JsonParser.parseString(result.get("data")).getAsJsonObject();

        assertEquals("1", result.get("schemaVersion"));
        assertEquals("1", result.get("eventSeq"));
        assertEquals("1", result.get("teamVersion"));
        assertEquals("MESSAGE_CHUNK", result.get("type"));
        assertEquals("hello", data.get("content").getAsString());
    }

    @Test
    public void rpcSinkUsesEventIdAsCallbackIdAndInstanceTransportGroup()
            throws Exception {
        AtomicReference<String> command = new AtomicReference<>();
        AtomicReference<String> group = new AtomicReference<>();
        AtomicReference<CmdResponseContent> response = new AtomicReference<>();
        AtomicReference<Thread> deliveryThread = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);
        RpcTeamEventSink sink = new RpcTeamEventSink((cmd, targetGroup, content) -> {
            command.set(cmd);
            group.set(targetGroup);
            response.set(content);
            deliveryThread.set(Thread.currentThread());
            delivered.countDown();
        });
        TeamEventEnvelope event = TeamEventEnvelope.next(
                new TeamRuntime(definition()), null, null,
                TeamEventType.TEAM_SNAPSHOT_READY, Collections.emptyMap());

        sink.publish(event);

        assertTrue(delivered.await(2, TimeUnit.SECONDS));
        assertEquals("acpTeamEvent", command.get());
        assertEquals("team-acp-instance", group.get());
        assertEquals(event.getEventId(), response.get().getCmdId());
        assertEquals(event.getEventId(), response.get().getResultMap().get("eventId"));
        assertTrue(deliveryThread.get().isDaemon());
        assertEquals("team-event-callback-delivery",
                deliveryThread.get().getName());
        sink.close();
    }

    @Test
    public void rpcSinkRetriesInDeliveryThreadAndDoesNotPropagateTransportFailure()
            throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(1);
        RpcTeamEventSink sink = new RpcTeamEventSink((cmd, group, response) -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                throw new RuntimeException("closed channel");
            }
            delivered.countDown();
        });

        sink.publish(TeamEventEnvelope.next(new TeamRuntime(definition()), null, null,
                TeamEventType.TEAM_SNAPSHOT_READY, Collections.emptyMap()));

        assertTrue(delivered.await(2, TimeUnit.SECONDS));
        assertEquals(3, attempts.get());
        sink.close();
    }

    @Test
    public void rpcSinkDropsEventWithoutPropagatingAfterRetryExhaustion()
            throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch exhausted = new CountDownLatch(3);
        RpcTeamEventSink sink = new RpcTeamEventSink((cmd, group, response) -> {
            attempts.incrementAndGet();
            exhausted.countDown();
            throw new RuntimeException("closed channel");
        });

        sink.publish(TeamEventEnvelope.next(new TeamRuntime(definition()), null, null,
                TeamEventType.TEAM_SNAPSHOT_READY, Collections.emptyMap()));

        assertTrue(exhausted.await(2, TimeUnit.SECONDS));
        assertEquals(3, attempts.get());
        sink.close();
    }

    @Test
    public void rpcSinkDeliversEventsInSubmissionOrder() throws Exception {
        List<String> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(3);
        RpcTeamEventSink sink = new RpcTeamEventSink((cmd, group, response) -> {
            delivered.add(response.getResultMap().get("type"));
            completed.countDown();
        }, 3);
        TeamRuntime runtime = new TeamRuntime(definition());

        sink.publish(TeamEventEnvelope.next(runtime, null, null,
                TeamEventType.TEAM_CREATE_ACCEPTED, Collections.emptyMap()));
        sink.publish(TeamEventEnvelope.next(runtime, null, null,
                TeamEventType.TEAM_READY, Collections.emptyMap()));
        assertTrue(sink.tryPublish(TeamEventEnvelope.next(runtime, null, null,
                TeamEventType.TEAM_DELETE_ACCEPTED, Collections.emptyMap())));

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(Arrays.asList("TEAM_CREATE_ACCEPTED", "TEAM_READY",
                "TEAM_DELETE_ACCEPTED"), delivered);
        sink.close();
    }

    @Test
    public void rpcSinkRejectsWithoutBlockingWhenQueueIsFull() throws Exception {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(2);
        RpcTeamEventSink sink = new RpcTeamEventSink((cmd, group, response) -> {
            callbackStarted.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                delivered.countDown();
            }
        }, 1);
        TeamRuntime runtime = new TeamRuntime(definition());

        sink.publish(TeamEventEnvelope.next(runtime, null, null,
                TeamEventType.TEAM_CREATE_ACCEPTED, Collections.emptyMap()));
        assertTrue(callbackStarted.await(2, TimeUnit.SECONDS));
        sink.publish(TeamEventEnvelope.next(runtime, null, null,
                TeamEventType.TEAM_READY, Collections.emptyMap()));
        assertFalse(sink.tryPublish(TeamEventEnvelope.next(runtime, null, null,
                TeamEventType.TEAM_DELETE_ACCEPTED, Collections.emptyMap())));

        assertEquals(1, sink.getQueueCapacity());
        assertEquals(1, sink.getQueuedEventCount());
        assertEquals(1L, sink.getRejectedEventCount());
        releaseCallback.countDown();
        assertTrue(delivered.await(2, TimeUnit.SECONDS));
        sink.close();
    }

    @Test
    public void rpcSinkRejectsSubmissionsAfterClose() {
        RpcTeamEventSink sink = new RpcTeamEventSink((cmd, group, response) -> { }, 1);
        sink.close();

        assertFalse(sink.tryPublish(TeamEventEnvelope.next(
                new TeamRuntime(definition()), null, null,
                TeamEventType.TEAM_SNAPSHOT_READY, Collections.emptyMap())));

        assertTrue(sink.isClosed());
        assertEquals(1L, sink.getRejectedEventCount());
    }

    private static TeamDefinition definition() {
        return TeamDefinition.creating("team-1", "owner-1", "Team",
                "team-acp-instance", "request-1",
                Arrays.asList(member("member-1"), member("member-2")), 100L);
    }

    private static TeamMemberDefinition member(String id) {
        return new TeamMemberDefinition(id, "source-" + id, "Robot " + id,
                "Robot " + id, "remark", "fingerprint-" + id);
    }
}
