package com.mola.cmd.proxy.app.acp.subagent;

import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DispatchBufferFilterChannelReplyTest {
    @Test
    public void removedReplyToOriginActionIsNotCaptured() {
        RecordingListener listener = new RecordingListener();
        DispatchBufferFilter filter = new DispatchBufferFilter(listener, false, false, true);

        filter.accept("{\"action\":\"reply_to_origin\",\"content\":\"done\"}");
        filter.flush();

        assertEquals(1, listener.messages.size());
        assertTrue(listener.messages.get(0).contains("reply_to_origin"));
        assertTrue(filter.getCapturedJsonList().isEmpty());
    }

    private static final class RecordingListener implements AcpResponseListener {
        private final List<String> messages = new ArrayList<>();
        @Override public void onMessage(String message) { messages.add(message); }
        @Override public void onToolCall(String id, String title, String status, JsonObject update) { }
        @Override public void onComplete(String response) { }
        @Override public void onError(Exception error) { }
    }
}
