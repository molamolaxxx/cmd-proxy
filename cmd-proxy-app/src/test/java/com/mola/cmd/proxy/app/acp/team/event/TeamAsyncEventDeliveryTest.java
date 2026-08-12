package com.mola.cmd.proxy.app.acp.team.event;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.team.MapTeamSourceRobotResolver;
import com.mola.cmd.proxy.app.acp.team.TeamClientRegistry;
import com.mola.cmd.proxy.app.acp.team.TeamCommandHandler;
import com.mola.cmd.proxy.app.acp.team.TeamManager;
import com.mola.cmd.proxy.app.acp.team.TeamStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TeamAsyncEventDeliveryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void createAndDeleteReturnWhileOrderedCallbacksAreBlocked()
            throws Exception {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(3);
        List<String> eventTypes = new CopyOnWriteArrayList<>();
        RpcTeamEventSink sink = new RpcTeamEventSink((command, group, response) -> {
            callbackStarted.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            eventTypes.add(response.getResultMap().get("type"));
            delivered.countDown();
        }, 8);
        TeamManager manager = manager(sink);
        TeamCommandHandler handler = new TeamCommandHandler(
                manager, "team-acp-instance");
        ExecutorService commands = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, String>> create = commands.submit(() ->
                    handler.handleCreate("rpc-create", one(createJson())));
            assertEquals("ACCEPTED", create.get(1, TimeUnit.SECONDS).get("code"));
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));

            Future<Map<String, String>> delete = commands.submit(() ->
                    handler.handleDelete("rpc-delete", one(deleteJson())));
            assertEquals("DELETED", delete.get(1, TimeUnit.SECONDS).get("code"));

            releaseCallback.countDown();
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("TEAM_CREATE_ACCEPTED",
                    "TEAM_DELETE_ACCEPTED", "TEAM_DELETED"), eventTypes);
        } finally {
            releaseCallback.countDown();
            manager.close();
            commands.shutdownNow();
        }
        assertTrue(sink.isClosed());
    }

    @Test
    public void managerShutdownDoesNotWaitForBlockedCallback() throws Exception {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        RpcTeamEventSink sink = new RpcTeamEventSink((command, group, response) -> {
            callbackStarted.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, 2);
        TeamManager manager = manager(sink);
        TeamCommandHandler handler = new TeamCommandHandler(
                manager, "team-acp-instance");
        ExecutorService commands = Executors.newSingleThreadExecutor();
        try {
            assertEquals("ACCEPTED",
                    handler.handleCreate("rpc-create", one(createJson())).get("code"));
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));

            Future<?> close = commands.submit(manager::close);
            close.get(1, TimeUnit.SECONDS);

            assertTrue(sink.isClosed());
        } finally {
            releaseCallback.countDown();
            manager.close();
            commands.shutdownNow();
        }
    }

    private TeamManager manager(RpcTeamEventSink sink) throws Exception {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("Robot One");
        robot.setSignature("Handles Robot One");
        return new TeamManager(
                new TeamStore(temporaryFolder.newFolder().toPath()),
                new TeamClientRegistry(),
                new MapTeamSourceRobotResolver(
                        Collections.singletonMap("group-1", robot)), sink);
    }

    private static String createJson() {
        return "{\"schemaVersion\":\"1\",\"requestId\":\"create-1\","
                + "\"teamId\":\"team-1\",\"ownerChatterId\":\"owner-1\","
                + "\"name\":\"Team\",\"members\":[{\"teamMemberId\":\"member-1\","
                + "\"sourceRobotId\":\"acp-Robot_One\","
                + "\"sourceGroupId\":\"group-1\",\"order\":0}]}";
    }

    private static String deleteJson() {
        return "{\"schemaVersion\":\"1\",\"requestId\":\"delete-1\","
                + "\"ownerChatterId\":\"owner-1\",\"teamId\":\"team-1\","
                + "\"expectedVersion\":1}";
    }

    private static String[] one(String value) {
        return new String[]{value};
    }
}
