package com.mola.cmd.proxy.app.acp.common;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class PathUtilsTest {

    @Test
    public void keepsLegacyResultWhenSanitizedNameIsNotEmpty() {
        assertEquals("home-mola-cmd-proxy",
                PathUtils.sanitizePath("/home/mola/cmd-proxy"));
        assertEquals("settlement",
                PathUtils.sanitizePath("结算-settlement-专家"));
    }

    @Test
    public void pureUnicodeNameUsesStableNonEmptyFallback() {
        String first = PathUtils.sanitizePath("结算专家");
        String second = PathUtils.sanitizePath("结算专家");

        assertEquals(first, second);
        assertTrue(first.startsWith("unicode-"));
        assertFalse(first.isEmpty());
    }

    @Test
    public void differentPureUnicodeNamesUseDifferentDirectories() {
        assertNotEquals(PathUtils.sanitizePath("结算专家"),
                PathUtils.sanitizePath("审核专家"));
    }
}
