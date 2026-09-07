package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TaskCardEventFactoryTest {
    @Test
    public void createsStableBusinessCardEnvelope() {
        JSONObject event = new JSONObject(true);
        event.put("eventId", "event-1");
        event.put("eventType", "TASK_ASSIGNED");
        event.put("contentVersion", 2L);
        JSONObject task = new JSONObject(true);
        task.put("id", "task-1");
        task.put("name", "Ship tasks");
        task.put("contentMarkdown", "  first\n\nsecond  ");
        task.put("revision", 3L);
        task.put("contentVersion", 2L);
        task.put("status", "START");
        task.put("target", new JSONObject(true));
        task.put("assignee", new JSONObject(true));
        JSONObject view = new JSONObject(true);
        view.put("task", task);

        JSONObject card = new TaskCardEventFactory().create(
                event, view, "ACCEPTED", "/tasks/task-1");

        assertEquals("STARWEAVE_TASK", card.getString("cardType"));
        assertEquals("event-1", card.getString("eventId"));
        assertEquals("first second", card.getString("summary"));
        assertTrue(card.containsKey("emittedAt"));
    }
}
