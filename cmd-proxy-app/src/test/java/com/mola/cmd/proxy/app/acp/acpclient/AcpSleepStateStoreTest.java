package com.mola.cmd.proxy.app.acp.acpclient;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AcpSleepStateStoreTest {

    @Test
    public void persistsSleepingLogicalIdsAcrossReload() throws Exception {
        Path root = Files.createTempDirectory("acp-sleep-state");
        Path file = root.resolve("state.json");

        AcpSleepStateStore first = new AcpSleepStateStore(file);
        first.markSleeping("main:one");
        assertTrue(new AcpSleepStateStore(file).isSleeping("main:one"));

        first.markAwake("main:one");
        assertFalse(new AcpSleepStateStore(file).isSleeping("main:one"));
    }
}
