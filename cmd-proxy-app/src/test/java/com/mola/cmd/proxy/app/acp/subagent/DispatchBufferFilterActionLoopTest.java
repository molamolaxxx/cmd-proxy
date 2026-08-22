package com.mola.cmd.proxy.app.acp.subagent;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DispatchBufferFilterActionLoopTest {

    @Test
    public void notifiesAsSoonAsFirstCompleteActionIsCaptured() {
        RecordingListener listener = new RecordingListener();
        List<String> captured = new ArrayList<>();
        DispatchBufferFilter filter = new DispatchBufferFilter(
                listener, false, false, true, captured::add);

        filter.accept("{\"action\":\"talk_to\",\"target\":\"Agent B\",");
        assertTrue(captured.isEmpty());

        filter.accept("\"content\":\"hello\"}");

        assertEquals(1, captured.size());
        assertTrue(captured.get(0).contains("\"action\":\"talk_to\""));
        assertTrue(listener.messages.isEmpty());
    }

    @Test
    public void onlyFirstEnabledActionTriggersTurnStopCallback() {
        RecordingListener listener = new RecordingListener();
        List<String> captured = new ArrayList<>();
        DispatchBufferFilter filter = new DispatchBufferFilter(
                listener, false, false, true, captured::add);

        filter.accept("{\"action\":\"talk_to\",\"target\":\"Agent B\",\"content\":\"one\"}");
        filter.accept("{\"action\":\"talk_to\",\"target\":\"Agent C\",\"content\":\"two\"}");

        assertEquals(1, captured.size());
        assertEquals(2, filter.getCapturedJsonList().size());
    }

    @Test
    public void disabledActionDoesNotTriggerTurnStopCallback() {
        RecordingListener listener = new RecordingListener();
        List<String> captured = new ArrayList<>();
        DispatchBufferFilter filter = new DispatchBufferFilter(
                listener, false, false, true, captured::add);

        filter.accept("{\"action\":\"dispatch_subagent\",\"tasks\":[]}");

        assertTrue(captured.isEmpty());
    }

    private static final class RecordingListener implements AcpResponseListener {
        private final List<String> messages = new ArrayList<>();
        @Override public void onMessage(String message) { messages.add(message); }
        @Override public void onToolCall(String id, String title, String status, JsonObject update) { }
        @Override public void onComplete(String response) { }
        @Override public void onError(Exception error) { }
    }
}
