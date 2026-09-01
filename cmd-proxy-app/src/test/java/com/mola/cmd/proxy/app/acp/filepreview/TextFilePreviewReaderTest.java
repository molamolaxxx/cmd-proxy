package com.mola.cmd.proxy.app.acp.filepreview;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class TextFilePreviewReaderTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void readsUtf8TextInsideWorkspace() throws Exception {
        Path workspace = temporaryFolder.newFolder("workspace").toPath();
        Path file = workspace.resolve("示例.java");
        Files.write(file, "class 示例 {\n}\n".getBytes(StandardCharsets.UTF_8));
        TextFilePreviewResult result = TextFilePreviewReader.read(
                "request", workspace.toString(), file.toString(), 1024, null);
        assertTrue(result.isAccepted());
        assertEquals("OK", result.getCode());
        assertTrue(result.toResultMap().get("data").contains("class 示例"));
    }

    @Test
    public void normalizesSlashPrefixedWindowsDrivePath() {
        assertEquals("C:/Users/mola/Test.java",
                TextFilePreviewReader.normalizeRequestedPath(
                        "/C:/Users/mola/Test.java", true));
        assertEquals("C:/Users/mola/Test.java",
                TextFilePreviewReader.normalizeRequestedPath(
                        "file:///C:/Users/mola/Test.java", true));
        assertEquals("/C:/Users/mola/Test.java",
                TextFilePreviewReader.normalizeRequestedPath(
                        "/C:/Users/mola/Test.java", false));
    }

    @Test
    public void readsFileOutsideWorkspace() throws Exception {
        Path workspace = temporaryFolder.newFolder("workspace").toPath();
        Path outside = Files.createTempFile("outside-preview", ".txt");
        try {
            Files.write(outside, "outside workspace".getBytes(StandardCharsets.UTF_8));
            TextFilePreviewResult result = TextFilePreviewReader.read(
                    "request", workspace.toString(), outside.toString(), 1024, null);
            assertTrue(result.isAccepted());
            assertEquals("OK", result.getCode());
            assertTrue(result.toResultMap().get("data").contains("outside workspace"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    public void rejectsBinaryAndOversizedFiles() throws Exception {
        Path workspace = temporaryFolder.newFolder("workspace").toPath();
        Path binary = workspace.resolve("image.bin");
        Files.write(binary, new byte[]{1, 0, 2, 3});
        TextFilePreviewResult binaryResult = TextFilePreviewReader.read(
                "binary", workspace.toString(), binary.toString(), 1024, null);
        assertFalse(binaryResult.isAccepted());
        assertEquals("BINARY_FILE", binaryResult.getCode());

        Path large = workspace.resolve("large.txt");
        Files.write(large, new byte[32]);
        TextFilePreviewResult largeResult = TextFilePreviewReader.read(
                "large", workspace.toString(), large.toString(), 8, null);
        assertFalse(largeResult.isAccepted());
        assertEquals("FILE_TOO_LARGE", largeResult.getCode());
    }
}
