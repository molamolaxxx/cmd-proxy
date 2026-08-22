package com.mola.cmd.proxy.app.acp.schedule;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScheduleContextInjectorTest {

    @Test
    public void scheduleExecutionStillReceivesScheduleCapabilityContext() {
        ScheduleContextInjector injector = new ScheduleContextInjector();

        String normal = injector.buildContext(true, false);
        String scheduled = injector.buildContext(true, true);

        assertTrue(scheduled.contains("<scheduled-tasks>"));
        assertTrue(scheduled.contains("schedule_task MCP 工具"));
        assertTrue(scheduled.contains("manage_schedule MCP 工具"));
        assertFalse(scheduled.contains("\"action\""));
        assertEquals(normal, scheduled);
    }

    @Test
    public void disabledRobotStillReceivesNoScheduleContext() {
        assertEquals("", new ScheduleContextInjector().buildContext(false, true));
    }
}
