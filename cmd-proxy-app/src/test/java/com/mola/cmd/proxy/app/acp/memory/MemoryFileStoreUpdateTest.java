package com.mola.cmd.proxy.app.acp.memory;

import com.mola.cmd.proxy.app.acp.memory.model.MemoryAction;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryEntry;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryIndex;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MemoryFileStoreUpdateTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private MemoryFileStore store;
    private String workspace;

    @Before
    public void setUp() throws Exception {
        workspace = temporaryFolder.newFolder("workspace").getAbsolutePath();
        store = new MemoryFileStore(
                temporaryFolder.newFolder("memory").getAbsolutePath());

        MemoryEntry entry = new MemoryEntry();
        entry.setId("memory_001");
        entry.setType("project");
        entry.setTitle("安全更新");
        entry.setSummary("旧概要");
        entry.setDetail("旧 detail 中的重要背景和边界");
        entry.setTags(new ArrayList<>(Arrays.asList("memory", "update")));
        entry.setRelatedSkills(new ArrayList<>(Collections.singletonList("cmd-proxy")));
        entry.setCreatedAt("2026-08-25T00:00:00+08:00");
        entry.setUpdatedAt("2026-08-25T00:00:00+08:00");
        store.writeDetail(workspace, entry);

        MemoryIndex index = new MemoryIndex();
        index.getMemories().add(entry);
        store.saveIndex(workspace, index);
    }

    @Test
    public void summaryOnlyUpdatePreservesOldDetail() throws Exception {
        MemoryAction action = updateAction();
        action.setSummary("新的决策概要");

        apply(action);

        MemoryEntry updated = currentEntry();
        assertEquals("新的决策概要", updated.getSummary());
        assertEquals("旧 detail 中的重要背景和边界",
                store.readDetailBody(updated));
    }

    @Test
    public void detailAppendAddsNewSectionWithoutReplacingOldDetail() throws Exception {
        MemoryAction action = updateAction();
        action.setDetailAppend("新增限制：只有写入开启时才更新访问统计。");

        apply(action);

        String detail = store.readDetailBody(currentEntry());
        assertTrue(detail.startsWith("旧 detail 中的重要背景和边界"));
        assertTrue(detail.matches("(?s).*## \\d{4}-\\d{2}-\\d{2} 更新.*"));
        assertTrue(detail.contains("新增限制：只有写入开启时才更新访问统计。"));
    }

    @Test
    public void explicitEmptyListsClearTagsAndSkillsWhileKeepingDetail() throws Exception {
        MemoryAction action = updateAction();
        action.setTags(Collections.emptyList());
        action.setRelatedSkills(Collections.emptyList());

        apply(action);

        MemoryEntry updated = currentEntry();
        assertTrue(updated.getTags().isEmpty());
        assertTrue(updated.getRelatedSkills().isEmpty());
        assertEquals("旧 detail 中的重要背景和边界",
                store.readDetailBody(updated));
    }

    @Test
    public void updateDetailFieldIsIgnoredInsteadOfReplacingOldDetail() throws Exception {
        MemoryAction action = updateAction();
        action.setDetail("UPDATE 不再接受的完整 detail");

        apply(action);

        String detail = store.readDetailBody(currentEntry());
        assertEquals("旧 detail 中的重要背景和边界", detail);
        assertFalse(detail.contains("UPDATE 不再接受的完整 detail"));
    }

    private MemoryAction updateAction() {
        MemoryAction action = new MemoryAction();
        action.setAction(MemoryAction.ActionType.UPDATE);
        action.setId("memory_001");
        return action;
    }

    private void apply(MemoryAction action) {
        MemoryIndex current = store.loadIndex(workspace);
        store.applyActions(workspace, Collections.singletonList(action), current, 30);
    }

    private MemoryEntry currentEntry() {
        return store.loadIndex(workspace).getMemories().get(0);
    }
}
