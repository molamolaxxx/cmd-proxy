package com.mola.cmd.proxy.app.acp;

import org.junit.Test;

import static org.junit.Assert.*;

public class AutoSleepConfigTest {
    @Test
    public void defaultsToDisabledWithThirtyMinuteIdleThreshold() {
        AutoSleepConfig config = new AutoSleepConfig();
        assertFalse(config.isEnabled());
        assertEquals(30, config.getIdleMinutes());
    }

    @Test
    public void robotOwnsIndependentAutoSleepConfiguration() {
        AcpRobotParam robot = new AcpRobotParam();
        AutoSleepConfig config = new AutoSleepConfig();
        config.setEnabled(true);
        config.setIdleMinutes(12);
        robot.setAutoSleep(config);
        assertTrue(robot.isAutoSleepEnabled());
        assertEquals(12, robot.getAutoSleep().getIdleMinutes());
    }
}
