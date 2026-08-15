package com.mola.cmd.proxy.app.acp.schedule;

import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleConfig;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduledTask;
import com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ScheduleOwnerIsolationTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void scheduledTaskPersistsChannelNeutralPrincipalForDerivedExecution()
            throws Exception {
        Path root = temporaryFolder.newFolder("principal").toPath();
        ScheduleOwnerKey owner = ScheduleOwnerKey.main("Robot");
        ScheduleTaskManager manager = new ScheduleTaskManager(root);
        AuthPrincipalContext principal = new AuthPrincipalContext(
                "user-a", "Alice", "WECOM", "wecom-main");

        ScheduledTask task = manager.createTask(owner, "run", "prompt",
                new ScheduleConfig("once", "+1h"), null, principal);

        assertEquals("user-a", task.getAuthPrincipalContext().getPrincipalId());
        String persisted = new String(Files.readAllBytes(
                root.resolve("Robot").resolve("tasks.json")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(persisted.contains("\"principalId\": \"user-a\""));
        assertTrue(persisted.contains("\"sourceType\": \"WECOM\""));
    }

    @Test
    public void mainAndTeamOwnersUseIndependentBackwardCompatiblePaths()
            throws Exception {
        Path root = temporaryFolder.newFolder("schedules").toPath();
        ScheduleTaskManager manager = new ScheduleTaskManager(root);
        ScheduleOwnerKey main = ScheduleOwnerKey.main("Robot One");
        ScheduleOwnerKey member = ScheduleOwnerKey.team(
                "owner-1", "team-1", "member-1", "Robot One");

        manager.createTask(main, "main", "normal",
                new ScheduleConfig("once", "+1h"));
        manager.createTask(member, "team", "isolated",
                new ScheduleConfig("once", "+1h"));

        assertTrue(Files.exists(root.resolve("Robot One/tasks.json")));
        assertTrue(Files.exists(
                root.resolve("team/team-1/member-1/tasks.json")));
        assertEquals(1, manager.listTasks(main).size());
        assertEquals(1, manager.listTasks(member).size());

        ScheduleTaskManager restored = new ScheduleTaskManager(root);
        restored.start();
        try {
            ScheduledTask restoredTask = restored.listTasks(member).get(0);
            assertEquals("owner-1", restoredTask.getOwner().getOwnerId());
            assertEquals("member-1",
                    restoredTask.getOwner().getTeamMemberId());
        } finally {
            restored.stop();
        }

        manager.cleanupTeam("team-1");
        assertFalse(Files.exists(root.resolve("team/team-1")));
        assertTrue(Files.exists(root.resolve("Robot One/tasks.json")));
    }

    @Test
    public void scopedCallbackReceivesTeamIdentity() throws Exception {
        Path root = temporaryFolder.newFolder("callback").toPath();
        ScheduleTaskManager manager = new ScheduleTaskManager(root);
        ScheduleOwnerKey member = ScheduleOwnerKey.team(
                "owner-1", "team-1", "member-1", "Robot");
        ScheduledTask task = manager.createTask(member, "run", "prompt",
                new ScheduleConfig("once", "+1h"));
        task.setNextRunAt(0L);
        CountDownLatch invoked = new CountDownLatch(1);
        AtomicReference<ScheduleOwnerKey> actual = new AtomicReference<>();
        manager.setScopedExecutionCallback((owner, taskId, groupName, prompt, principal) -> {
            actual.set(owner);
            invoked.countDown();
            return false;
        });

        Method scan = ScheduleTaskManager.class.getDeclaredMethod("scan");
        scan.setAccessible(true);
        scan.invoke(manager);

        assertTrue(invoked.await(2, TimeUnit.SECONDS));
        assertEquals(member, actual.get());
        assertEquals("owner-1", actual.get().getOwnerId());
    }

    @Test
    public void groupNameAndSessionBindingPersistAndRemainOwnerScoped()
            throws Exception {
        Path root = temporaryFolder.newFolder("groups").toPath();
        ScheduleOwnerKey main = ScheduleOwnerKey.main("Robot One");
        ScheduleOwnerKey member = ScheduleOwnerKey.team(
                "owner-1", "team-1", "member-1", "Robot One");
        ScheduleTaskManager manager = new ScheduleTaskManager(root);

        ScheduledTask mainTask = manager.createTask(main, "main", "prompt",
                new ScheduleConfig("once", "+1h"), " daily ");
        ScheduledTask teamTask = manager.createTask(member, "team", "prompt",
                new ScheduleConfig("once", "+1h"), "daily");
        manager.bindGroupSession(main, "daily", "session-main");
        manager.bindGroupSession(member, "daily", "session-team");

        assertEquals("daily", mainTask.getGroupName());
        assertEquals("daily", teamTask.getGroupName());
        assertEquals("session-main", manager.findGroupSession(main, "daily"));
        assertEquals("session-team", manager.findGroupSession(member, "daily"));
        assertTrue(Files.exists(root.resolve("Robot One/groups.json")));
        assertTrue(Files.exists(
                root.resolve("team/team-1/member-1/groups.json")));

        ScheduleTaskManager restored = new ScheduleTaskManager(root);
        restored.start();
        try {
            assertEquals("session-main",
                    restored.findGroupSession(main, "daily"));
            assertEquals("session-team",
                    restored.findGroupSession(member, "daily"));
            assertEquals("daily",
                    restored.listTasks(main).get(0).getGroupName());
        } finally {
            restored.stop();
        }
    }

    @Test
    public void scheduleJsonAppliesOneGroupNameToAllCreatedTasks()
            throws Exception {
        Path root = temporaryFolder.newFolder("json-group").toPath();
        ScheduleTaskManager manager = new ScheduleTaskManager(root);
        ScheduleOwnerKey owner = ScheduleOwnerKey.main("Robot");

        manager.handleAction("{\"action\":\"schedule_task\","
                + "\"groupName\":\"daily\",\"tasks\":["
                + "{\"title\":\"one\",\"prompt\":\"p1\","
                + "\"schedule\":{\"type\":\"once\",\"expr\":\"+1h\"}},"
                + "{\"title\":\"two\",\"prompt\":\"p2\","
                + "\"schedule\":{\"type\":\"once\",\"expr\":\"+2h\"}}]}",
                owner);

        assertEquals(2, manager.listTasks(owner).size());
        assertTrue(manager.listTasks(owner).stream()
                .allMatch(task -> "daily".equals(task.getGroupName())));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsafeTeamPersistenceIdentity() {
        ScheduleOwnerKey.team("owner", "../team", "member", "Robot");
    }
}
