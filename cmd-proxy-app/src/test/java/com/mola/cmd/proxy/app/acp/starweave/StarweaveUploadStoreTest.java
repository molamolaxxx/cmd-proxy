package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.assertEquals;

public class StarweaveUploadStoreTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void stagesWithSafeNameAndEnforcesSessionGeneration() throws Exception {
        StarweaveUploadStore store = new StarweaveUploadStore(
                temporaryFolder.newFolder("uploads").toPath());
        JSONObject metadata = store.stage("group", "session", 2L,
                "../../note.txt", Base64.getEncoder().encodeToString(
                        "hello".getBytes(StandardCharsets.UTF_8)));

        assertEquals("note.txt", metadata.getString("fileName"));
        StarweaveUploadStore.ResolvedUpload upload = store.resolve(
                metadata.getString("uploadId"), "group", "session", 2L);
        assertEquals("hello", new String(upload.bytes, StandardCharsets.UTF_8));
        assertIllegal(() -> store.resolve(metadata.getString("uploadId"),
                "group", "session", 3L));
    }

    @Test
    public void rejectsInvalidBase64AndEmptyFiles() throws Exception {
        StarweaveUploadStore store = new StarweaveUploadStore(
                temporaryFolder.newFolder("invalid").toPath());
        assertIllegal(() -> store.stage("g", "s", 1L, "x.txt", "%%%"));
        assertIllegal(() -> store.stage("g", "s", 1L, "x.txt", ""));
    }

    private static void assertIllegal(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
