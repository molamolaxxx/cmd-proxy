package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.task.service.TaskService;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TaskDispatchServiceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void assignsThenDurablyAcknowledgesDeliveryInTaskOrder() throws Exception {
        TaskService service = new TaskService(temporary.newFolder("tasks").toPath(), "instance-1");
        try {
            JSONObject created = service.create(createRequest());
            String taskId = created.getJSONObject("task").getString("id");
            Directory directory = new Directory();
            TaskTargetResolver resolver = new TaskTargetResolver(directory, new Random(1L));
            RecordingAdapter adapter = new RecordingAdapter();
            TaskDispatchService dispatch = new TaskDispatchService(
                    service, resolver, ignored -> adapter, "http://127.0.0.1/tasks");
            try {
                assertEquals(1, dispatch.processAvailable());
                JSONObject assigned = service.get(taskId, new JSONObject(true));
                assertEquals("acp-agent-a", assigned.getJSONObject("task")
                        .getJSONObject("assignee").getString("robotId"));

                assertEquals(1, dispatch.processAvailable());
                assertNotNull(adapter.card);
                assertEquals("TASK_ASSIGNED", adapter.card.getString("eventType"));
                assertTrue(adapter.prompt.contains("Do not merely report completion in chat"));
                assertTrue(adapter.prompt.contains("taskEventSeq:"));
                assertTrue(adapter.prompt.contains("allowedTransitions as authoritative"));
                JSONArray delivery = service.get(taskId, new JSONObject(true))
                        .getJSONArray("delivery");
                assertEquals("ACKNOWLEDGED",
                        delivery.getJSONObject(delivery.size() - 1).getString("state"));
            } finally {
                dispatch.close();
            }
        } finally {
            service.close();
        }
    }

    @Test public void statusPromptsDistinguishSuspendCancelAndContentChange(){JSONObject card=new JSONObject();card.put("eventId","e");card.put("taskEventSeq",3L);card.put("taskId","t");card.put("revision",4L);card.put("contentVersion",2L);card.put("eventType","TASK_STATUS_CHANGED");card.put("status","SUSPENDED");assertTrue(TaskDispatchService.agentPrompt(card).contains("earliest safe boundary"));card.put("status","CANCELLED");assertTrue(TaskDispatchService.agentPrompt(card).contains("has been cancelled"));card.put("eventType","TASK_CONTENT_CHANGED");card.put("status","START");assertTrue(TaskDispatchService.agentPrompt(card).contains("new work version"));}

    private static JSONObject createRequest() {
        JSONObject request = new JSONObject(true);
        request.put("name", "Task A");
        request.put("contentMarkdown", "Do the work");
        request.put("attachmentIds", new JSONArray());
        request.put("creatorName", "Human");
        request.put("requestId", "create-1");
        JSONObject target = new JSONObject(true);
        target.put("type", "AGENT");
        target.put("robotId", "acp-agent-a");
        request.put("target", target);
        return request;
    }

    private static final class Directory implements TaskExecutionDirectory {
        @Override
        public JSONObject resolveAgent(JSONObject target) {
            JSONObject result = new JSONObject(true);
            result.put("instanceId", "instance-1");
            result.put("ownerId", "owner-1");
            result.put("surface", "STARWEAVE");
            result.put("robotId", "acp-agent-a");
            result.put("groupId", "group-a");
            return result;
        }

        @Override
        public List<JSONObject> listTeamMembers(JSONObject target) {
            return Collections.emptyList();
        }
    }

    private static final class RecordingAdapter implements TaskDeliveryAdapter {
        private JSONObject card;
        private String prompt;

        @Override
        public DeliveryResult deliver(JSONObject assignee, JSONObject card, String agentPrompt) {
            this.card = card;
            this.prompt = agentPrompt;
            return DeliveryResult.accepted("receipt-1");
        }
    }
}
