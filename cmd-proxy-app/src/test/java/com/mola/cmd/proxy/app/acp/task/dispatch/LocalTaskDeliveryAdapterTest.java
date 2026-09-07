package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.task.service.TaskService;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

public class LocalTaskDeliveryAdapterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void duplicateAcceptanceDoesNotInjectSecondPrompt() throws Exception {
        TaskService service = new TaskService(temporary.newFolder("tasks").toPath(), "instance-1");
        try {
            Sink sink = new Sink(true);
            LocalTaskDeliveryAdapter adapter = new LocalTaskDeliveryAdapter(
                    service.getRepository(), sink);
            try {
                JSONObject card = card("event-1");

                String first = adapter.deliver(new JSONObject(true), card, "prompt")
                        .getReceipt();
                String duplicate = adapter.deliver(new JSONObject(true), card, "prompt")
                        .getReceipt();

                assertEquals(first, duplicate);
                assertEquals(1, sink.calls);
            } finally {
                adapter.close();
            }
        } finally {
            service.close();
        }
    }

    @Test
    public void recoversPersistedReceiptWhenTargetBecomesReady() throws Exception {
        TaskService service = new TaskService(temporary.newFolder("recovery").toPath(), "instance-1");
        try {
            Sink sink = new Sink(false);
            LocalTaskDeliveryAdapter adapter = new LocalTaskDeliveryAdapter(
                    service.getRepository(), sink);
            try {
                adapter.deliver(new JSONObject(true), card("event-2"), "prompt");
                sink.accept = true;

                assertEquals(1, adapter.recoverPending(10));
                assertEquals(2, sink.calls);
                assertEquals(0, adapter.recoverPending(10));
            } finally {
                adapter.close();
            }
        } finally {
            service.close();
        }
    }

    @Test
    public void suppressesExecutableReceiptAfterTaskIsSuspended() throws Exception {
        TaskService service = new TaskService(temporary.newFolder("stale").toPath(), "instance-1");
        try {
            JSONObject request = JSONObject.parseObject("{name:'Task',contentMarkdown:'work',"
                    + "target:{type:'AGENT'},creatorName:'Human',requestId:'create'}");
            JSONObject task = service.create(request).getJSONObject("task");
            JSONObject assignee = JSONObject.parseObject(
                    "{instanceId:'instance-1',agentId:'agent-1',groupId:'group-1'}");
            service.getRepository().assign(task.getString("id"), assignee);
            Sink sink = new Sink(false);
            LocalTaskDeliveryAdapter adapter = new LocalTaskDeliveryAdapter(service, sink);
            try {
                JSONObject card = card("event-stale");
                card.put("eventType", "TASK_ASSIGNED");
                card.put("taskId", task.getString("id"));
                card.put("contentVersion", 1L);
                adapter.deliver(assignee, card, "prompt");
                JSONObject current = service.get(task.getString("id"), new JSONObject(true))
                        .getJSONObject("task");
                service.updateStatus(task.getString("id"), JSONObject.parseObject(
                        "{status:'SUSPENDED',expectedRevision:" + current.getLongValue("revision")
                                + ",observedContentVersion:1,requestId:'suspend',"
                                + "actorName:'Human'}"));

                assertEquals(1, adapter.recoverPending(10));
                assertEquals(1, sink.calls);
                assertEquals(0, adapter.recoverPending(10));
            } finally {
                adapter.close();
            }
        } finally {
            service.close();
        }
    }

    private static JSONObject card(String eventId) {
        JSONObject card = new JSONObject(true);
        card.put("eventId", eventId);
        return card;
    }

    private static final class Sink implements TaskPromptSink {
        private boolean accept;
        private int calls;

        private Sink(boolean accept) { this.accept = accept; }

        @Override
        public boolean submit(JSONObject assignee, JSONObject card, String agentPrompt) {
            calls++;
            return accept;
        }
    }
}
