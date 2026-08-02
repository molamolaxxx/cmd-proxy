package com.mola.cmd.proxy.app.acp.schedule;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ScheduleContextInjectorTest {

    @Test
    public void scheduleExecutionStillReceivesScheduleCapabilityContext() {
        ScheduleContextInjector injector = new ScheduleContextInjector();

        String normal = injector.buildContext(true, false);
        String scheduled = injector.buildContext(true, true);

        assertTrue(scheduled.contains("<scheduled-tasks>"));
        assertTrue(scheduled.contains("\"action\":\"schedule_task\""));
        assertTrue(scheduled.contains("\"action\":\"manage_schedule\""));
        assertEquals(normal, scheduled);
    }

    @Test
    public void disabledRobotStillReceivesNoScheduleContext() {
        assertEquals("", new ScheduleContextInjector().buildContext(false, true));
    }
}
