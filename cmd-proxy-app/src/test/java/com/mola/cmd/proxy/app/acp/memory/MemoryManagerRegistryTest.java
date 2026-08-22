package com.mola.cmd.proxy.app.acp.memory;

import com.alibaba.fastjson.JSON;
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

    @Test
    public void memoryExecutionDefaultsToModelAndRobotModeDisablesModelOverride() {
        MemoryConfig legacy = JSON.parseObject(
                "{\"model\":\"legacy-memory-model\"}", MemoryConfig.class);
        assertEquals("model", legacy.getExecutionMode());
        assertEquals("legacy-memory-model", legacy.getExecutionModel());

        legacy.setExecutionMode("robot");
        legacy.setRobotName("Memory Robot");
        assertEquals("Memory Robot", legacy.getRobotName());
        assertEquals(null, legacy.getExecutionModel());
    }

    @Test
    public void selectedExecutionRobotDoesNotChangeMemoryOwnerScope() throws Exception {
        String root = temporaryFolder.newFolder("owner-scope-memory").getAbsolutePath();
        MemoryConfig config = new MemoryConfig();
        config.setBaseDir(root);
        config.setScope("robot");
        config.setExecutionMode("robot");
        config.setRobotName("Executor");
        AcpRobotParam owner = new AcpRobotParam();
        owner.setName("Owner");
        AcpRobotParam executor = new AcpRobotParam();
        executor.setName("Executor");

        MemoryManagerRegistry registry = new MemoryManagerRegistry();
        MemoryManager manager = registry.getOrCreate(
                "owner-group", config, owner, executor, "/executor/workspace");
        manager.incrementSessionCount("/source/workspace");

        assertEquals(1, new MemoryFileStore(root, "Owner")
                .loadDreamState("/source/workspace").getSessionsSinceLastDream());
        assertEquals(0, new MemoryFileStore(root, "Executor")
                .loadDreamState("/source/workspace").getSessionsSinceLastDream());
        registry.shutdownAll();
    }

    @Test
    public void missingExecutionRobotKeepsMemoryReadsAvailable() throws Exception {
        MemoryConfig config = new MemoryConfig();
        config.setBaseDir(temporaryFolder.newFolder("missing-executor-memory")
                .getAbsolutePath());
        AcpRobotParam owner = new AcpRobotParam();
        owner.setName("Owner");
        MemoryManagerRegistry registry = new MemoryManagerRegistry();

        MemoryManager manager = registry.getOrCreate(
                "missing-executor", config, owner, null, null);

        assertEquals("", manager.buildMemoryPrompt("/source/workspace"));
        manager.triggerDream("/source/workspace");
        registry.shutdownAll();
    }
}
