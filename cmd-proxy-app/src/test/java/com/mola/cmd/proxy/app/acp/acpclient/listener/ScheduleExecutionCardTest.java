package com.mola.cmd.proxy.app.acp.acpclient.listener;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ScheduleExecutionCardTest {

    @Test
    public void rendersExpandedExecutionCardWithTaskPrompt() {
        List<String> frames = new ArrayList<>();
        DefaultAcpResponseListener listener =
                new DefaultAcpResponseListener("main",
                        (content, end) -> frames.add(content));

        listener.onScheduleEvent("SCHEDULE_EXECUTE",
                "[定时任务触发] 任务: 日报\n\n生成日报", true);

        assertEquals(1, frames.size());
        assertTrue(frames.get(0).contains("<details class=\"tool-call\" open>"));
        assertTrue(frames.get(0).contains("⏰ 定时任务执行中..."));
        assertTrue(frames.get(0).contains("任务: 日报"));
    }
}
