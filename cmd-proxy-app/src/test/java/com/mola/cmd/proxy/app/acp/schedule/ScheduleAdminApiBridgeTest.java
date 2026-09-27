package com.mola.cmd.proxy.app.acp.schedule;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleConfig;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduledTask;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ScheduleAdminApiBridgeTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void listsFiltersUpdatesAndDeletesAcrossOwners() throws Exception {
        Path root = temporaryFolder.newFolder("schedule-admin").toPath();
        ScheduleTaskManager manager = new ScheduleTaskManager(root);
        ScheduledTask main = manager.createTask(ScheduleOwnerKey.main("assistant"),
                "每日早报", "生成早报", new ScheduleConfig("cron", "0 8 * * *"));
        manager.createTask(ScheduleOwnerKey.team("owner", "team-a", "member-a", "researcher"),
                "一次提醒", "发送提醒",
                new ScheduleConfig("once", "2099-01-01T09:00:00"));
        ScheduleAdminApiBridge.install(manager);
        try {
            JSONObject all = ScheduleAdminApiBridge.listTasks(1, 10, null, null, null, null);
            assertEquals(2, all.getLongValue("total"));
            JSONObject team = ScheduleAdminApiBridge.listTasks(1, 10, null,
                    "TEAM", "once", "WAITING");
            assertEquals(1, team.getLongValue("total"));

            JSONObject body = new JSONObject(true);
            body.put("title", "每日 AI 早报");
            body.put("prompt", "生成 AI 早报");
            body.put("groupName", "daily-{yyyyMMdd}");
            JSONObject schedule = new JSONObject(true);
            schedule.put("type", "cron");
            schedule.put("expr", "30 8 * * *");
            body.put("schedule", schedule);
            JSONObject updated = ScheduleAdminApiBridge.update(
                    main.getOwner().getPersistencePath(), main.getId(), body);
            assertEquals("每日 AI 早报", updated.getString("title"));
            assertEquals("daily-{yyyyMMdd}", updated.getString("groupName"));
            assertEquals("30 8 * * *", updated.getJSONObject("schedule").getString("expr"));

            JSONObject removed = ScheduleAdminApiBridge.delete(
                    main.getOwner().getPersistencePath(), main.getId());
            assertTrue(removed.getBooleanValue("deleted"));
            assertNull(manager.findTaskSnapshot(main.getOwner().getPersistencePath(), main.getId()));
        } finally {
            ScheduleAdminApiBridge.clear(manager);
        }
    }

    @Test
    public void pagesDurableExecutionRecordsWithoutExposingTaskSecrets() throws Exception {
        Path root = temporaryFolder.newFolder("schedule-executions").toPath();
        ScheduleTaskManager manager = new ScheduleTaskManager(root);
        ScheduleOwnerKey owner = ScheduleOwnerKey.main("assistant");
        ScheduledTask task = manager.createTask(owner, "早报", "secret prompt",
                new ScheduleConfig("cron", "0 8 * * *"));
        ScheduleExecutionJournal journal = new ScheduleExecutionJournal(root);
        String id = journal.begin(owner, task, 1, System.currentTimeMillis());
        journal.markAccepted(id, System.currentTimeMillis());
        manager.completeExecutionTurn(id, true, null);
        ScheduleAdminApiBridge.install(manager);
        try {
            JSONObject page = ScheduleAdminApiBridge.executions(1, 10,
                    owner.getPersistencePath(), task.getId(), null);
            assertEquals(1, page.getLongValue("total"));
            JSONObject record = page.getJSONArray("items").getJSONObject(0);
            assertEquals("SUCCEEDED", record.getString("status"));
            assertEquals("TURN_COMPLETED", record.getString("resultCode"));
            assertTrue(!record.containsKey("prompt"));
        } finally {
            ScheduleAdminApiBridge.clear(manager);
        }
    }
}
