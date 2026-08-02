package com.mola.cmd.proxy.app.acp.memory;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.memory.model.DreamState;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class MemoryManagerRegistryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void aliasesShareStorageLockAndDoNotLoseConcurrentSessionWrites()
            throws Exception {
        MemoryConfig config = new MemoryConfig();
        config.setBaseDir(temporaryFolder.newFolder("memory")
                .getAbsolutePath());
        config.setScope("workspace");
        config.setDreamEnabled(false);
        config.setSubClientTimeout(1);
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("Robot");

        MemoryManagerRegistry registry = new MemoryManagerRegistry();
        MemoryManager main = registry.getOrCreate("group-1", config, robot);
        MemoryManager team = registry.getOrCreate(
                "team-acp-member-1", config, robot);
        assertSame(main, registry.getOrCreate("group-1", config, robot));

        String workspace = "/workspace/shared";
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> writes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            writes.add(pool.submit(() -> main.incrementSessionCount(workspace)));
            writes.add(pool.submit(() -> team.incrementSessionCount(workspace)));
        }
        for (Future<?> write : writes) {
            write.get(5, TimeUnit.SECONDS);
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        DreamState state = new MemoryFileStore(config.getBaseDir())
                .loadDreamState(workspace);
        assertEquals(200, state.getSessionsSinceLastDream());
        assertEquals(1, registry.getLockRegistry().size());
        registry.shutdownAll();
    }

    @Test
    public void robotScopeProducesDifferentStorageLocks() throws Exception {
        MemoryScopeLockRegistry locks = new MemoryScopeLockRegistry();
        String root = temporaryFolder.newFolder("robot-memory")
                .getAbsolutePath();
        locks.lockFor(new MemoryFileStore(root, "Robot A"), "/workspace");
        locks.lockFor(new MemoryFileStore(root, "Robot B"), "/workspace");
        assertEquals(2, locks.size());
    }
}
