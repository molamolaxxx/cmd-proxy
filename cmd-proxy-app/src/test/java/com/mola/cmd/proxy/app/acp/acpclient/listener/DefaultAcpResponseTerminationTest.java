package com.mola.cmd.proxy.app.acp.acpclient.listener;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class DefaultAcpResponseTerminationTest {

    @Test
    public void interruptedMarkerIsAttachedOnlyToNextTerminalFrame() {
        DefaultAcpResponseListener listener = new DefaultAcpResponseListener(
                "group-1", (content, end) -> { });
        listener.markNextTermination("INTERRUPTED");

        Map<String, String> chunk = listener.buildResultMap("old output", false);
        Map<String, String> terminal = listener.buildResultMap("已停止。", true);
        Map<String, String> later = listener.buildResultMap("done", true);

        assertFalse(chunk.containsKey("termination"));
        assertEquals("INTERRUPTED", terminal.get("termination"));
        assertFalse(terminal.containsKey("interruptMessageId"));
        assertFalse(later.containsKey("termination"));
    }

    @Test
    public void failedCancellationClearsPreparedMarker() {
        DefaultAcpResponseListener listener = new DefaultAcpResponseListener(
                "group-1", (content, end) -> { });
        listener.markNextTermination("INTERRUPTED");
        listener.clearNextTermination();

        assertFalse(listener.buildResultMap("done", true)
                .containsKey("termination"));
    }
}
