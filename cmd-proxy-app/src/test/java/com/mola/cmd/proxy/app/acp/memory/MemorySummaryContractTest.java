package com.mola.cmd.proxy.app.acp.memory;

import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryEntry;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryIndex;
import com.mola.cmd.proxy.app.acp.memory.prompt.DreamPromptTemplate;
import com.mola.cmd.proxy.app.acp.memory.prompt.MemoryPromptTemplate;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MemorySummaryContractTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void extractionPromptRequiresDecisionReadySummary() {
        String prompt = MemoryPromptTemplate.build(
                "USER: 测试对话", new MemoryIndex(), Collections.emptyList());

        assertTrue(prompt.contains("可直接用于未来决策"));
        assertTrue(prompt.contains("40~120"));
        assertTrue(prompt.contains("必须单行"));
        assertTrue(prompt.contains("新增明细使用 detailAppend"));
        assertTrue(prompt.contains("UPDATE 是部分更新"));
        assertFalse(prompt.contains("仅标注主题和类型，不要写结论"));
        assertFalse(prompt.contains("提供完整的更新后内容（非增量）"));
    }

    @Test
    public void dreamPromptCanNormalizeTopicLikeSummaryWithoutDroppingDetail() {
        MemoryIndex index = new MemoryIndex();
        MemoryEntry entry = memory("memory_001", "记忆模块", "记忆模块实现");
        index.getMemories().add(entry);

        String prompt = DreamPromptTemplate.build(index, Collections.emptyMap());

        assertTrue(prompt.contains("决策摘要规范化"));
        assertTrue(prompt.contains("可仅 UPDATE summary"));
        assertTrue(prompt.contains("不要为了改写 summary 删除仍然有效的 detail"));
    }

    @Test
    public void injectedContextAllowsSummaryForOrdinaryDecisionsButRequiresDetailForChanges() {
        String workspace = temporaryFolder.getRoot().getAbsolutePath();
        MemoryFileStore store = new MemoryFileStore(
                temporaryFolder.getRoot().toPath().resolve("memory").toString());
        MemoryEntry entry = memory("memory_001", "记忆模块", "修改记忆前必须先核验当前实现");
        entry.setFile(temporaryFolder.getRoot().toPath()
                .resolve("memory_001.md").toAbsolutePath().toString());
        MemoryIndex index = new MemoryIndex();
        index.getMemories().add(entry);
        store.saveIndex(workspace, index);

        MemoryConfig config = new MemoryConfig();
        config.setIndexMaxLines(100);
        String context = new MemoryLoader(store, config).buildMemoryPrompt(workspace);

        assertTrue(context.contains("普通判断可直接依据概要"));
        assertTrue(context.contains("涉及代码修改、实现边界、证据核验、冲突信息或高风险操作时，必须读取明细文件"));
        assertTrue(context.contains("修改记忆前必须先核验当前实现"));
    }

    private MemoryEntry memory(String id, String title, String summary) {
        MemoryEntry entry = new MemoryEntry();
        entry.setId(id);
        entry.setType("project");
        entry.setTitle(title);
        entry.setSummary(summary);
        entry.setDetail("完整的背景、依据和适用边界");
        entry.setCreatedAt("2026-08-25T00:00:00+08:00");
        entry.setUpdatedAt("2026-08-25T00:00:00+08:00");
        return entry;
    }
}
