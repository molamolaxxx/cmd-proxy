package com.mola.cmd.proxy.app.acp.acpclient.listener;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LifecycleWakeCardTest {

    @Test
    public void wakeCardBodyIsWrappedInCodeFenceSoMolaChatRendersCard() {
        List<String> frames = new ArrayList<>();
        DefaultAcpResponseListener listener =
                new DefaultAcpResponseListener("main",
                        (content, end) -> frames.add(content));

        listener.onLifecycleEvent("AGENT_WAKE", "SLEEP", "READY", 5169L, true);

        assertEquals(1, frames.size());
        String frame = frames.get(0);
        assertTrue(frame.contains("<details class=\"tool-call\">"
                + "<summary>🌤️ ✅ 智能体已唤醒</summary>"
                + "<div class=\"tool-call-body\">\n\n```\n"));
        assertTrue(frame.contains("状态：SLEEP → READY"));
        assertTrue(frame.contains("耗时：5169 ms"));
        assertTrue(frame.contains("已按空闲轮转规则创建新会话。"));
        assertTrue(frame.contains("\n```\n\n</div></details>\n"));
        assertFalse(frame.contains("<code>"));
    }

    @Test
    public void nonWakeLifecycleEventEmitsNothing() {
        List<String> frames = new ArrayList<>();
        DefaultAcpResponseListener listener =
                new DefaultAcpResponseListener("main",
                        (content, end) -> frames.add(content));

        listener.onLifecycleEvent("AGENT_SLEEP", "READY", "SLEEP", 1L, false);

        assertTrue(frames.isEmpty());
    }
}
