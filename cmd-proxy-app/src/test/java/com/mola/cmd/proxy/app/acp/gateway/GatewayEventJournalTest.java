package com.mola.cmd.proxy.app.acp.gateway;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.gateway.model.GatewayEvent;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

public class GatewayEventJournalTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void appendsDurablyAndReplaysInSequence() throws Exception {
        java.io.File database = temporaryFolder.newFile("gateway.db");
        try (GatewayEventJournal journal = new GatewayEventJournal(database.toPath())) {
            GatewayEvent first = journal.append("gateway-1", "session-1", 1L,
                    "turn-1", null, "assistant.message.delta", value("text", "one"),
                    value("nativeType", "MESSAGE_CHUNK"));
            GatewayEvent second = journal.append("gateway-1", "session-1", 1L,
                    "turn-1", "tool-1", "tool_call.updated", value("status", "completed"),
                    value("nativeType", "TOOL_CALL"));

            assertEquals(1L, first.getEventSeq());
            assertEquals(2L, second.getEventSeq());
            assertNotEquals(first.getEventId(), second.getEventId());
            assertEquals(2L, journal.lastSeq("gateway-1", "session-1", 1L));

            JSONArray replay = journal.list("gateway-1", "session-1", 1L, 1L, 100);
            assertEquals(1, replay.size());
            assertEquals("tool_call.updated", replay.getJSONObject(0).getString("type"));
            assertEquals(2L, replay.getJSONObject(0).getJSONObject("event")
                    .getLongValue("eventSeq"));
            assertEquals("TOOL_CALL", replay.getJSONObject(0).getJSONObject("event")
                    .getJSONObject("source").getString("nativeType"));
        }
    }

    @Test
    public void idempotencyReturnsOriginalResponseAndRejectsChangedRequest() throws Exception {
        java.io.File database = temporaryFolder.newFile("idempotency.db");
        AtomicInteger calls = new AtomicInteger();
        try (GatewayEventJournal journal = new GatewayEventJournal(database.toPath())) {
            JSONObject first = journal.idempotent("gateway-1", "message.send", "key-1",
                    "fingerprint-a", () -> value("turnId", "turn-1-" + calls.incrementAndGet()));
            JSONObject replay = journal.idempotent("gateway-1", "message.send", "key-1",
                    "fingerprint-a", () -> value("turnId", "should-not-run"));
            assertEquals(first, replay);
            assertEquals(1, calls.get());

            try {
                journal.idempotent("gateway-1", "message.send", "key-1",
                        "fingerprint-b", () -> value("turnId", "other"));
                fail("expected idempotency conflict");
            } catch (GatewayException expected) {
                assertEquals(409, expected.getHttpStatus());
                assertEquals("IDEMPOTENCY_CONFLICT", expected.getCode());
            }
        }
    }

    private static JSONObject value(String key, Object value) {
        JSONObject result = new JSONObject(true);
        result.put(key, value);
        return result;
    }
}
