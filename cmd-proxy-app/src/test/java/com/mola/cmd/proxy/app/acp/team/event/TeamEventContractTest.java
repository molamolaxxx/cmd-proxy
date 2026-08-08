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
import java.util.Map;
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
    public void rpcSinkUsesEventIdAsCallbackIdAndInstanceTransportGroup() {
        AtomicReference<String> command = new AtomicReference<>();
        AtomicReference<String> group = new AtomicReference<>();
        AtomicReference<CmdResponseContent> response = new AtomicReference<>();
        RpcTeamEventSink sink = new RpcTeamEventSink((cmd, targetGroup, content) -> {
            command.set(cmd);
            group.set(targetGroup);
            response.set(content);
        });
        TeamEventEnvelope event = TeamEventEnvelope.next(
                new TeamRuntime(definition()), null, null,
                TeamEventType.TEAM_SNAPSHOT_READY, Collections.emptyMap());

        sink.publish(event);

        assertEquals("acpTeamEvent", command.get());
        assertEquals("team-acp-instance", group.get());
        assertEquals(event.getEventId(), response.get().getCmdId());
        assertEquals(event.getEventId(), response.get().getResultMap().get("eventId"));
    }

    @Test
    public void rpcSinkRetriesAndDoesNotPropagateTransportFailure() {
        AtomicInteger attempts = new AtomicInteger();
        RpcTeamEventSink sink = new RpcTeamEventSink((cmd, group, response) -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("closed channel");
            }
        });

        sink.publish(TeamEventEnvelope.next(new TeamRuntime(definition()), null, null,
                TeamEventType.TEAM_SNAPSHOT_READY, Collections.emptyMap()));

        assertEquals(3, attempts.get());
    }

    @Test
    public void rpcSinkDropsEventWithoutPropagatingAfterRetryExhaustion() {
        AtomicInteger attempts = new AtomicInteger();
        RpcTeamEventSink sink = new RpcTeamEventSink((cmd, group, response) -> {
            attempts.incrementAndGet();
            throw new RuntimeException("closed channel");
        });

        sink.publish(TeamEventEnvelope.next(new TeamRuntime(definition()), null, null,
                TeamEventType.TEAM_SNAPSHOT_READY, Collections.emptyMap()));

        assertEquals(3, attempts.get());
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
