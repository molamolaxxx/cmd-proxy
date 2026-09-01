package com.mola.cmd.proxy.app.acp.memory;

import com.mola.cmd.proxy.app.acp.memory.model.MemoryEntry;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryIndex;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class MemoryFileStoreAccessTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private MemoryFileStore store;
    private String workspace;
    private MemoryEntry entry;

    @Before
    public void setUp() throws Exception {
        workspace = temporaryFolder.newFolder("workspace").getAbsolutePath();
        store = new MemoryFileStore(
                temporaryFolder.newFolder("memory").getAbsolutePath());
        entry = new MemoryEntry();
        entry.setId("memory_001");
        entry.setType("project");
        entry.setTitle("Bash访问统计");
        entry.setSummary("Bash 读取已知记忆明细路径时应记录一次访问");
        entry.setDetail("用于验证访问统计的明细内容");
        entry.setCreatedAt("2026-08-25T00:00:00+08:00");
        entry.setUpdatedAt("2026-08-25T00:00:00+08:00");
        store.writeDetail(workspace, entry);
        MemoryIndex index = new MemoryIndex();
        index.getMemories().add(entry);
        store.saveIndex(workspace, index);
    }

    @Test
    public void bashCommandReferencingKnownDetailIsCountedOncePerToolCall() {
        String command = "sed -n '1,120p' '" + entry.getFile() + "'";

        int touched = store.touchMemoriesReferenced(workspace,
                Arrays.asList(command, entry.getFile(), command));

        assertEquals(1, touched);
        MemoryEntry reloaded = find("memory_001");
        assertEquals(1, reloaded.getAccessCount());
        assertNotNull(reloaded.getLastAccessedAt());
    }

    @Test
    public void unknownMemoryLikePathAndYamlIdDoNotIncrementCount() {
        int touched = store.touchMemoriesReferenced(workspace, Arrays.asList(
                "sed -n '1,120p' /tmp/memories/unknown.md",
                "id: memory_001\ntitle: Bash访问统计"));

        assertEquals(0, touched);
        assertEquals(0, find("memory_001").getAccessCount());
    }

    @Test
    public void directStructuredPathStillIncrementsCount() {
        int touched = store.touchMemoriesReferenced(workspace,
                Collections.singletonList(entry.getFile()));

        assertEquals(1, touched);
        assertEquals(1, find("memory_001").getAccessCount());
    }

    private MemoryEntry find(String id) {
        for (MemoryEntry candidate : store.loadIndex(workspace).getMemories()) {
            if (id.equals(candidate.getId())) return candidate;
        }
        throw new AssertionError("memory not found: " + id);
    }
}
