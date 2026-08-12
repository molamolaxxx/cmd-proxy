package com.mola.cmd.proxy.app.acp.common;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PathResolverTest {

    @Test
    public void extractsMarkedPathAfterProfileWarning() throws Exception {
        String output = "/etc/profile: ulimit: Operation not permitted\n"
                + PathResolver.PATH_OUTPUT_MARKER
                + "/home/opsuser/.nvm/versions/node/v22.23.2/bin:/usr/bin\n";

        String path = PathResolver.extractMarkedPath(
                new BufferedReader(new StringReader(output)));

        assertEquals("/home/opsuser/.nvm/versions/node/v22.23.2/bin:/usr/bin", path);
    }

    @Test
    public void ignoresUnmarkedShellOutput() throws Exception {
        String output = "welcome\n/etc/profile: warning\n";

        String path = PathResolver.extractMarkedPath(
                new BufferedReader(new StringReader(output)));

        assertNull(path);
    }
}
