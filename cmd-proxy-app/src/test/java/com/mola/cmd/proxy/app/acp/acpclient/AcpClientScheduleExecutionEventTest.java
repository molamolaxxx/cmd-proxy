package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AcpClientScheduleExecutionEventTest {

    @Test
    public void scheduleExecutionPublishesExpandedStartEvent() {
        RecordingListener listener = new RecordingListener();
        String prompt = "[定时任务触发] 任务: 日报\n\n生成日报";

        AcpClient.notifyScheduleExecutionStarted(
                listener, prompt, PromptOptions.forScheduleExecution());

        assertEquals("SCHEDULE_EXECUTE", listener.eventType);
        assertEquals(prompt, listener.detail);
        assertTrue(listener.expanded);
    }

    @Test
    public void ordinaryPromptDoesNotPublishScheduleStartEvent() {
        RecordingListener listener = new RecordingListener();

        AcpClient.notifyScheduleExecutionStarted(
                listener, "普通消息", PromptOptions.defaults());

        assertFalse(listener.called);
    }

    private static final class RecordingListener implements AcpResponseListener {
        private boolean called;
        private String eventType;
        private String detail;
        private boolean expanded;

        @Override
        public void onMessage(String text) {
        }

        @Override
        public void onToolCall(String toolCallId, String title, String status,
                               JsonObject update) {
        }

        @Override
        public void onScheduleEvent(String eventType, String detail,
                                    boolean expanded) {
            this.called = true;
            this.eventType = eventType;
            this.detail = detail;
            this.expanded = expanded;
        }

        @Override
        public void onComplete(String fullResponse) {
        }

        @Override
        public void onError(Exception error) {
        }
    }
}
