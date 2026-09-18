package com.mola.cmd.proxy.app.acp.configui;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

public class ConfigUiJarUpdateTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void calculatesJarMd5() throws Exception {
        Path jar = temporaryFolder.newFile("cmd-proxy.jar").toPath();
        Files.write(jar, "cmd-proxy".getBytes(StandardCharsets.UTF_8));

        assertEquals("82b563451085c152b928663de3b17097", ConfigUiServer.calculateMd5(jar));
    }

    @Test
    public void acceptsMd5sumSidecarFormat() throws Exception {
        assertEquals("3ce350537e03467611917b523a7fcb38",
                ConfigUiServer.parseMd5(
                        "3CE350537E03467611917B523A7FCB38  /tmp/cmd-proxy.jar\n"));
    }

    @Test(expected = IOException.class)
    public void rejectsInvalidRemoteMd5() throws Exception {
        ConfigUiServer.parseMd5("not-an-md5");
    }
}
