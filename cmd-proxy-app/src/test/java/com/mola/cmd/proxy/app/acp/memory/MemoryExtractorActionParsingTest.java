package com.mola.cmd.proxy.app.acp.memory;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryAction;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MemoryExtractorActionParsingTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void parsesDetailAppendAndDistinguishesMissingFromEmptyLists() {
        MemoryExtractor extractor = extractor();
        try {
            List<MemoryAction> actions = extractor.parseActions("["
                    + "{\"action\":\"UPDATE\",\"id\":\"memory_001\","
                    + "\"detailAppend\":\"新增约束\"},"
                    + "{\"action\":\"UPDATE\",\"id\":\"memory_002\","
                    + "\"tags\":[],\"relatedSkills\":[]}]");

            assertEquals(2, actions.size());
            assertEquals("新增约束", actions.get(0).getDetailAppend());
            assertNull(actions.get(0).getTags());
            assertNull(actions.get(0).getRelatedSkills());
            assertNotNull(actions.get(1).getTags());
            assertNotNull(actions.get(1).getRelatedSkills());
            assertTrue(actions.get(1).getTags().isEmpty());
            assertTrue(actions.get(1).getRelatedSkills().isEmpty());
        } finally {
            extractor.shutdown();
        }
    }

    private MemoryExtractor extractor() {
        MemoryConfig config = new MemoryConfig();
        MemoryFileStore store = new MemoryFileStore(
                temporaryFolder.getRoot().toPath().resolve("memory").toString());
        return new MemoryExtractor(config, store, new AcpRobotParam());
    }
}
