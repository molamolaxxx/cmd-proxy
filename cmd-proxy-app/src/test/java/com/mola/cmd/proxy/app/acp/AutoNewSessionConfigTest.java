package com.mola.cmd.proxy.app.acp;

import org.junit.Test;

import static org.junit.Assert.*;

public class AutoNewSessionConfigTest {

    @Test
    public void defaultsAreDisabledWithRequestedIntervals() {
        AutoNewSessionConfig config = new AutoNewSessionConfig();

        assertFalse(config.isEnabled());
        assertEquals(360, config.getCheckIntervalMinutes());
        assertEquals(180, config.getIdleMinutes());
    }

    @Test
    public void nonPositiveIntervalsFallBackToDefaults() {
        AutoNewSessionConfig config = new AutoNewSessionConfig();
        config.setCheckIntervalMinutes(0);
        config.setIdleMinutes(-1);

        assertEquals(360, config.getCheckIntervalMinutes());
        assertEquals(180, config.getIdleMinutes());
    }

    @Test
    public void configurationBelongsToEachRobotInstance() {
        AcpRobotParam first = new AcpRobotParam();
        AcpRobotParam second = new AcpRobotParam();
        AutoNewSessionConfig config = new AutoNewSessionConfig();
        config.setEnabled(true);
        config.setCheckIntervalMinutes(12);
        config.setIdleMinutes(7);
        first.setAutoNewSession(config);

        assertTrue(first.isAutoNewSessionEnabled());
        assertEquals(12, first.getAutoNewSession().getCheckIntervalMinutes());
        assertEquals(7, first.getAutoNewSession().getIdleMinutes());
        assertFalse(second.isAutoNewSessionEnabled());
        assertNull(second.getAutoNewSession());
    }
}
