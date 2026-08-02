package com.mola.cmd.proxy.app.acp.team;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.schedule.ScheduleTaskManager;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleConfig;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToRequest;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberState;
import com.mola.cmd.proxy.app.acp.team.model.TeamOperationRecord;
import com.mola.cmd.proxy.app.acp.team.model.TeamState;
import com.mola.cmd.proxy.app.acp.team.talkto.TeamTalkToDispatcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class TeamDeleteLifecycleTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void deleteIsIdempotentArchivesHistoryAndReturnsResourcesToZero()
            throws Exception {
        Fixture fixture = fixture(2_000L, false);
        Path history = fixture.sessionRoot.resolve(
                "team/team-1/member-1/session-1/turn_0000.json");
        Files.createDirectories(history.getParent());
        Files.write(history, "{}".getBytes(StandardCharsets.UTF_8));
        fixture.schedule.createTask(ScheduleOwnerKey.team(
                        "owner-1", "team-1", "member-1", "Robot"),
                "task", "prompt", new ScheduleConfig("once", "+1h"));
        fixture.manager.getOrCreateTalkToDispatcher("team-1");

        String payload = deleteJson("delete-1", 2L);
        Map<String, String> first =
                fixture.handler.handleDelete("rpc-1", one(payload));
        Map<String, String> replay =
                fixture.handler.handleDelete("rpc-2", one(payload));

        assertEquals("true", first.get("accepted"));
        assertEquals("DELETED", first.get("code"));
        assertEquals("4", first.get("teamVersion"));
        assertEquals(first.get("data"), replay.get("data"));
        assertFalse(fixture.store.loadTeam("team-1").isPresent());
        assertEquals(TeamState.DELETED,
                fixture.store.loadTombstone("team-1").get().getFinalState());
        assertTrue(Files.exists(fixture.archiveRoot.resolve(
                "team-1/member-1/session-1/turn_0000.json")));
        assertFalse(Files.exists(fixture.scheduleRoot.resolve(
                "team/team-1")));
        assertTrue(fixture.manager.resourceSnapshot().isZero());
        assertTrue(fixture.events.stream().anyMatch(event ->
                event.getType().name().equals("TEAM_DELETE_ACCEPTED")));
        assertTrue(fixture.events.stream().anyMatch(event ->
                event.getType().name().equals("TEAM_DELETED")));

        JsonObject data = JsonParser.parseString(first.get("data"))
                .getAsJsonObject();
        assertEquals("DELETED", data.getAsJsonObject("team")
                .get("state").getAsString());
        assertEquals(0, data.getAsJsonArray("warnings").size());
        assertEquals(0, data.getAsJsonObject("resources")
                .get("clients").getAsInt());
        fixture.manager.close();
    }

    @Test
    public void deletingBarrierRejectsSendAndTimeoutBecomesReapableWarning()
            throws Exception {
        Fixture fixture = fixture(25L, true);
        TeamTalkToDispatcher dispatcher =
                fixture.manager.getOrCreateTalkToDispatcher("team-1");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Map<String, String>> deleting = executor.submit(
                () -> fixture.handler.handleDelete(
                        "rpc-delete", one(deleteJson("delete-timeout", 2L))));
        assertTrue(fixture.blockingClient.closeStarted.await(
                2, TimeUnit.SECONDS));

        Map<String, String> send = fixture.handler.handleSend(
                "rpc-send", one(memberSendJson()));
        Map<String, String> cancel = fixture.handler.handleCancel(
                "rpc-cancel", one(memberCommandJson()));
        assertEquals("TEAM_DELETING", send.get("code"));
        assertEquals("TEAM_DELETING", cancel.get("code"));
        assertTrue(dispatcher.deliver(
                new TalkToRequest("member-2", "late", 0),
                "member-1", "", null).contains("not accepting"));
        try {
            fixture.schedule.createTask(ScheduleOwnerKey.team(
                            "owner-1", "team-1", "member-1", "Robot"),
                    "late", "late", new ScheduleConfig("once", "+1h"));
            fail("DELETING Team schedule must be blocked");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("DELETING"));
        }

        Map<String, String> result = deleting.get(2, TimeUnit.SECONDS);
        assertEquals("DELETED_WITH_WARNINGS", result.get("code"));
        assertEquals(1, fixture.manager.resourceSnapshot().getPendingCleanup());
        fixture.blockingClient.releaseClose.countDown();
        assertTrue(fixture.blockingClient.closeFinished.await(
                2, TimeUnit.SECONDS));
        fixture.manager.startResourceReaper();
        new TeamResourceReaper(fixture.manager).runOnce();
        assertEquals(0, fixture.manager.resourceSnapshot().getPendingCleanup());
        executor.shutdownNow();
        fixture.manager.close();
    }

    private static String memberCommandJson() {
        return "{\"schemaVersion\":\"1\",\"ownerChatterId\":\"owner-1\","
                + "\"teamId\":\"team-1\",\"teamMemberId\":\"member-1\"}";
    }

    @Test
    public void expectedVersionAndOwnerAreValidatedBeforeDelete() throws Exception {
        Fixture fixture = fixture(2_000L, false);
        Map<String, String> version = fixture.handler.handleDelete(
                "rpc-version", one(deleteJson("delete-version", 1L)));
        Map<String, String> owner = fixture.handler.handleDelete(
                "rpc-owner", one(deleteJson("delete-owner", 2L)
                        .replace("owner-1", "owner-2")));

        assertEquals("VERSION_CONFLICT", version.get("code"));
        assertEquals("UNAUTHORIZED", owner.get("code"));
        assertEquals(TeamState.READY,
                fixture.manager.getRuntime("team-1").get()
                        .getDefinition().getState());
        fixture.manager.close();
    }

    @Test
    public void restartResumesPersistedDeletingState() throws Exception {
        Fixture fixture = fixture(2_000L, false);
        fixture.manager.close();
        TeamDefinition deleting = fixture.store.loadTeam("team-1").get()
                .beginDeleting("delete-recovery", System.currentTimeMillis());
        fixture.store.saveTeam(deleting);
        fixture.store.saveOperation(new TeamOperationRecord(
                "delete-recovery", TeamOperationRecord.Operation.DELETE,
                "payload-hash", "team-1",
                TeamOperationRecord.Status.ACCEPTED, null,
                System.currentTimeMillis(),
                System.currentTimeMillis() + 60_000L));

        TeamManager recovered = new TeamManager(
                fixture.store, new TeamClientRegistry(), spec -> {
                    throw new TeamSourceResolutionException(
                            com.mola.cmd.proxy.app.acp.team.model.TeamErrorCode
                                    .SOURCE_ROBOT_NOT_FOUND, "unused");
                }, event -> { }, null,
                new TeamHistoryArchiver(
                        fixture.sessionRoot.resolve("team"),
                        fixture.archiveRoot), 1_000L);
        recovered.setScheduleCleanup(fixture.schedule::cleanupTeam);
        recovered.recoverPersistedDefinitions();

        long deadline = System.currentTimeMillis() + 2_000L;
        while ((!fixture.store.loadTombstone("team-1").isPresent()
                || !fixture.store.loadOperation("delete-recovery").isPresent()
                || fixture.store.loadOperation("delete-recovery").get()
                .getResultSnapshot() == null)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(fixture.store.loadTombstone("team-1").isPresent());
        assertFalse(fixture.store.loadTeam("team-1").isPresent());
        assertFalse(recovered.getRuntime("team-1").isPresent());
        assertNotNull(fixture.store.loadOperation("delete-recovery")
                .get().getResultSnapshot());
        recovered.close();
    }

    private Fixture fixture(long timeoutMillis, boolean blocking)
            throws Exception {
        Path storeRoot = temporaryFolder.newFolder("teams").toPath();
        Path sessionRoot = temporaryFolder.newFolder("session").toPath();
        Path archiveRoot = temporaryFolder.newFolder("archive").toPath();
        Path scheduleRoot = temporaryFolder.newFolder("schedule").toPath();
        TeamStore store = new TeamStore(storeRoot);
        TeamDefinition creating = TeamDefinition.creating(
                "team-1", "owner-1", "Team", "team-acp-instance",
                "create-1", Arrays.asList(member("member-1"),
                        member("member-2")), 1L);
        List<TeamMemberDefinition> readyMembers = Arrays.asList(
                creating.getMembers().get(0).withState(
                        TeamMemberState.READY, "session-1", null),
                creating.getMembers().get(1).withState(
                        TeamMemberState.READY, "session-2", null));
        TeamDefinition ready = creating.transitionWithMembers(
                TeamState.READY, readyMembers, null, 2L);
        store.saveTeam(creating);
        store.saveTeam(ready);

        TeamClientRegistry registry = new TeamClientRegistry();
        BlockingClient first = client("member-1", blocking);
        BlockingClient second = client("member-2", false);
        registry.register("team-1", "member-1", first);
        registry.register("team-1", "member-2", second);
        List<TeamEventEnvelope> events = new ArrayList<>();
        TeamManager manager = new TeamManager(
                store, registry, spec -> {
                    throw new TeamSourceResolutionException(
                            com.mola.cmd.proxy.app.acp.team.model.TeamErrorCode
                                    .SOURCE_ROBOT_NOT_FOUND, "unused");
                }, events::add, null,
                new TeamHistoryArchiver(
                        sessionRoot.resolve("team"), archiveRoot),
                timeoutMillis);
        assertTrue(manager.attachPersistedDefinition(ready));
        ScheduleTaskManager schedule = new ScheduleTaskManager(scheduleRoot);
        manager.setScheduleCleanup(schedule::cleanupTeam);
        manager.setScheduleOrphanCleanup(
                schedule::cleanupOrphanTeams, schedule::teamOwnerCount,
                schedule::teamOwnerCount);
        return new Fixture(store, manager,
                new TeamCommandHandler(manager, "team-acp-instance"),
                schedule, events, first, sessionRoot, archiveRoot, scheduleRoot);
    }

    private static TeamMemberDefinition member(String id) {
        return new TeamMemberDefinition(
                id, "source-" + id, "Robot " + id,
                "Robot " + id, "remark", "fingerprint-" + id);
    }

    private static BlockingClient client(String memberId, boolean blocking) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("Robot");
        AcpClientIdentity identity = AcpClientIdentity.team(
                "team-acp-" + memberId, "team-acp-instance",
                "team/team-1/" + memberId, "owner-1",
                "team-1", memberId, "Robot");
        return new BlockingClient(identity, robot, blocking);
    }

    private static String deleteJson(String requestId, long version) {
        return "{\"schemaVersion\":\"1\",\"requestId\":\"" + requestId
                + "\",\"ownerChatterId\":\"owner-1\",\"teamId\":\"team-1\","
                + "\"expectedVersion\":" + version + "}";
    }

    private static String memberSendJson() {
        return "{\"schemaVersion\":\"1\",\"ownerChatterId\":\"owner-1\","
                + "\"teamId\":\"team-1\",\"teamMemberId\":\"member-1\","
                + "\"message\":\"late\"}";
    }

    private static String[] one(String value) {
        return new String[]{value};
    }

    private static final class BlockingClient extends AcpClient {
        private final boolean blocking;
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);
        private final CountDownLatch closeFinished = new CountDownLatch(1);

        private BlockingClient(AcpClientIdentity identity, AcpRobotParam robot,
                               boolean blocking) {
            super(".", identity, robot);
            this.blocking = blocking;
            state.set(AbstractAcpClient.State.READY);
        }

        @Override
        public void close() throws IOException {
            closeStarted.countDown();
            try {
                if (blocking) {
                    releaseClose.await(5, TimeUnit.SECONDS);
                }
                state.set(AbstractAcpClient.State.CLOSED);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            } finally {
                closeFinished.countDown();
            }
        }
    }

    private static final class Fixture {
        private final TeamStore store;
        private final TeamManager manager;
        private final TeamCommandHandler handler;
        private final ScheduleTaskManager schedule;
        private final List<TeamEventEnvelope> events;
        private final BlockingClient blockingClient;
        private final Path sessionRoot;
        private final Path archiveRoot;
        private final Path scheduleRoot;

        private Fixture(TeamStore store, TeamManager manager,
                        TeamCommandHandler handler, ScheduleTaskManager schedule,
                        List<TeamEventEnvelope> events,
                        BlockingClient blockingClient, Path sessionRoot,
                        Path archiveRoot, Path scheduleRoot) {
            this.store = store;
            this.manager = manager;
            this.handler = handler;
            this.schedule = schedule;
            this.events = events;
            this.blockingClient = blockingClient;
            this.sessionRoot = sessionRoot;
            this.archiveRoot = archiveRoot;
            this.scheduleRoot = scheduleRoot;
        }
    }
}
