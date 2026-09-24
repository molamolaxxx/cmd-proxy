package com.mola.cmd.proxy.app.acp.memory;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryIndex;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MemoryDreamerAdmissionTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void oneScopeCannotStartTwoDreamsEvenAcrossManagers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String baseDir = temporaryFolder.getRoot().getAbsolutePath();
        MemoryFileStore blockedStore = new MemoryFileStore(baseDir) {
            @Override public MemoryIndex loadIndex(String workspacePath) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return new MemoryIndex();
            }
        };
        MemoryDreamer first = new MemoryDreamer(new MemoryConfig(), blockedStore, new AcpRobotParam());
        MemoryDreamer second = new MemoryDreamer(new MemoryConfig(),
                new MemoryFileStore(baseDir), new AcpRobotParam());
        String workspace = temporaryFolder.newFolder("workspace").getAbsolutePath();
        try {
            assertTrue(first.submitDream(workspace));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(second.isDreaming(workspace));
            assertFalse(second.submitDream(workspace));
        } finally {
            release.countDown();
            first.shutdownNow();
            second.shutdownNow();
        }
    }
}
