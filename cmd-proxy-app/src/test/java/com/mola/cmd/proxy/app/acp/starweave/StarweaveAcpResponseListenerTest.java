package com.mola.cmd.proxy.app.acp.starweave;

import com.google.gson.JsonObject;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class StarweaveAcpResponseListenerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void listenerPreservesStructuredOrderAndTerminalBoundary() {
        StarweaveSessionEventStore store = new StarweaveSessionEventStore(10);
        StarweaveAcpResponseListener listener = new StarweaveAcpResponseListener(
                "group-1", () -> "session-1", () -> 3L, store);
        JsonObject update = new JsonObject();
        update.addProperty("rawOutput", "ok");

        listener.onMessage("hello");
        listener.onToolCall("tool-1", "exec", "completed", update);
        listener.onComplete("");

        List<StarweaveSessionEvent> events = store.snapshot(
                "group-1", "session-1", 0L);
        assertEquals(3, events.size());
        assertEquals("ASSISTANT_MESSAGE_DELTA", events.get(0).getType());
        assertEquals("TOOL_CALL_UPDATED", events.get(1).getType());
        assertEquals("ok", events.get(1).toJson().getJSONObject("payload")
                .getJSONObject("update").getString("rawOutput"));
        assertEquals("TURN_COMPLETED", events.get(2).getType());
        assertEquals(1L, events.get(0).getEventSeq());
        assertEquals(3L, events.get(2).getGeneration());
        assertNotNull(events.get(0).getTurnId());
        assertEquals(events.get(0).getTurnId(), events.get(1).getTurnId());
        assertEquals(events.get(1).getTurnId(), events.get(2).getTurnId());
    }

    @Test
    public void boundedStoreDropsOldestEvents() {
        StarweaveSessionEventStore store = new StarweaveSessionEventStore(2);
        store.append("group-1", "session-1", 1L, "ONE", null);
        store.append("group-1", "session-1", 1L, "TWO", null);
        store.append("group-1", "session-1", 1L, "THREE", null);

        List<StarweaveSessionEvent> events = store.snapshot(
                "group-1", "session-1", 0L);
        assertEquals(2, events.size());
        assertEquals("TWO", events.get(0).getType());
        assertEquals("THREE", events.get(1).getType());
    }

    @Test
    public void durableStoreSurvivesRestartAndContinuesSequence() throws Exception {
        Path root = temporaryFolder.newFolder("events").toPath();
        StarweaveSessionEventStore first = new StarweaveSessionEventStore(10, root);
        first.append("group-1", "session-1", "turn-1", 1L, "ONE", null);
        first.append("group-1", "session-2", "turn-2", 2L, "TWO", null);

        StarweaveSessionEventStore restored = new StarweaveSessionEventStore(10, root);
        List<StarweaveSessionEvent> sessionOne = restored.snapshot(
                "group-1", "session-1", 0L);
        assertEquals(1, sessionOne.size());
        assertEquals("turn-1", sessionOne.get(0).getTurnId());
        StarweaveSessionEvent next = restored.append(
                "group-1", "session-1", "turn-3", 3L, "THREE", null);
        assertEquals(3L, next.getEventSeq());
        assertTrue(restored.hasDurableEvents("group-1", "session-1"));
    }

    @Test
    public void readReportsResyncAndFiltersLateGeneration() {
        StarweaveSessionEventStore store = new StarweaveSessionEventStore(2);
        store.append("group-1", "session-1", 1L, "OLD_ONE", null);
        store.append("group-1", "session-1", 1L, "OLD_TWO", null);
        store.append("group-1", "session-1", 2L, "CURRENT", null);

        StarweaveSessionEventStore.ReadResult result = store.read(
                "group-1", "session-1", 0L, 2L);
        assertFalse(result.isResyncRequired());
        assertEquals(1, result.getEvents().size());
        assertEquals("CURRENT", result.getEvents().get(0).getType());

        StarweaveSessionEventStore.ReadResult stale = store.read(
                "group-1", "session-1", 0L, null);
        assertFalse(stale.isResyncRequired());
        StarweaveSessionEventStore.ReadResult missed = store.read(
                "group-1", "session-1", 1L, null);
        assertFalse(missed.isResyncRequired());
        store.append("group-1", "session-1", 2L, "CURRENT_TWO", null);
        StarweaveSessionEventStore.ReadResult trulyMissed = store.read(
                "group-1", "session-1", 1L, null);
        assertTrue(trulyMissed.isResyncRequired());
    }
}
