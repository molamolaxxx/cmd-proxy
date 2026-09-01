package com.mola.cmd.proxy.app.acp.starweave;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.*;

public class StarweaveSessionIndexTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void activeStateSurvivesReloadAndGenerationIncreases() throws Exception {
        Path path = temporaryFolder.newFolder("index").toPath().resolve("sessions.json");
        StarweaveSessionIndex index = new StarweaveSessionIndex(path);

        StarweaveSessionIndex.Entry first = index.activate(
                "Robot", "group-1", "session-1");
        StarweaveSessionIndex.Entry second = index.updateSession(
                "group-1", "session-2");

        assertEquals(1L, first.getGeneration());
        assertEquals(2L, second.getGeneration());
        StarweaveSessionIndex.Entry restored =
                new StarweaveSessionIndex(path).get("group-1");
        assertNotNull(restored);
        assertTrue(restored.isActive());
        assertEquals("session-2", restored.getCurrentSessionId());
        assertEquals(2L, restored.getGeneration());
    }

    @Test
    public void deleteCreatesForceNewTombstoneAndNextActivateClearsIt() throws Exception {
        Path path = temporaryFolder.newFolder("delete").toPath().resolve("sessions.json");
        StarweaveSessionIndex index = new StarweaveSessionIndex(path);
        index.activate("Robot", "group-1", "session-1");

        StarweaveSessionIndex.Entry deleted = index.markDeleted("group-1");

        assertFalse(deleted.isActive());
        assertTrue(deleted.isForceNewOnNextOpen());
        assertNull(deleted.getCurrentSessionId());
        StarweaveSessionIndex.Entry reopened = index.activate(
                "Robot", "group-1", "session-2");
        assertTrue(reopened.isActive());
        assertFalse(reopened.isForceNewOnNextOpen());
        assertEquals(3L, reopened.getGeneration());
    }
}
