package com.mola.cmd.proxy.app.utils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CmdProxyHomeTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void generatedInstanceIdContainsMachineNameAndSurvivesMachineNameChange()
            throws Exception {
        Path home = temporaryFolder.newFolder("data-root").toPath();

        String first = CmdProxyHome.resolveInstanceId(home, null, "ubuntu-local");
        String second = CmdProxyHome.resolveInstanceId(home, null, "renamed-host");

        assertTrue(first.startsWith("ubuntu-local-"));
        assertEquals(first, second);
        assertEquals(first, new String(Files.readAllBytes(home.resolve("instance-id")),
                StandardCharsets.UTF_8).trim());
    }

    @Test
    public void explicitInstanceIdOverridesAndUpdatesPersistedValue() throws Exception {
        Path home = temporaryFolder.newFolder("explicit-root").toPath();
        CmdProxyHome.resolveInstanceId(home, null, "initial-host");

        String explicit = CmdProxyHome.resolveInstanceId(
                home, "prod 106.54.193.10", "ignored-host");

        assertEquals("prod-106.54.193.10", explicit);
        assertEquals(explicit, CmdProxyHome.resolveInstanceId(
                home, null, "another-host"));
    }
}
