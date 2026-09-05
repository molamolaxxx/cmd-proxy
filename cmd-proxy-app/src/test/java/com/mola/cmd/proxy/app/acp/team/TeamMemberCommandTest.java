package com.mola.cmd.proxy.app.acp.team;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.AutoNewSessionConfig;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.acpclient.PromptOptions;
import com.mola.cmd.proxy.app.acp.schedule.ScheduleTaskManager;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberState;
import com.mola.cmd.proxy.app.acp.team.model.TeamState;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCreateSpec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class TeamMemberCommandTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void sendValidatesRouteAndPreservesFilenameToContentFilesContract()
            throws Exception {
        Fixture fixture = fixture();
        String payload = basePayload()
                .replace("}", ",\"message\":\"hello\",\"files\":[{\"a.png\":\"aGk=\"}]}");

        Map<String, String> result =
                fixture.handler.handleSend("rpc-send", new String[]{payload});

        assertEquals("true", result.get("accepted"));
        assertEquals("QUEUED", result.get("code"));
        assertEquals("hello", fixture.current.get().message);
        assertEquals("aGk=", fixture.current.get().files.get(0).get("a.png"));
        assertEquals(TeamMemberState.BUSY,
                fixture.manager.getRuntime("team-1").get().getDefinition()
                        .getMembers().get(0).getState());
        assertTrue(fixture.events.stream().anyMatch(
                event -> event.getType().name().equals("MEMBER_STATE_CHANGED")));
        fixture.manager.close();
    }

    @Test
    public void statusContextCancelAndSessionListUseCanonicalDataEnvelope()
            throws Exception {
        Fixture fixture = fixture();

        Map<String, String> status =
                fixture.handler.handleGetStatus("rpc-status", one(basePayload()));
        Map<String, String> context =
                fixture.handler.handleGetContextUsage("rpc-context", one(basePayload()));
        Map<String, String> cancel =
                fixture.handler.handleCancel("rpc-cancel", one(basePayload()));
        Map<String, String> sessions =
                fixture.handler.handleListSessions("rpc-list",
                        one(basePayload().replace("}", ",\"limit\":7}")));

        JsonObject statusData = json(status);
        assertEquals("READY", statusData.get("teamState").getAsString());
        assertEquals("READY", statusData.get("memberState").getAsString());
        assertEquals(42.5, json(context)
                .get("contextUsagePercentage").getAsDouble(), 0.001);
        assertTrue(fixture.current.get().cancelled);
        assertTrue(json(sessions).getAsJsonArray("sessions").isEmpty());
        assertEquals("true", cancel.get("accepted"));
        fixture.manager.close();
    }

    @Test
    public void historyRestoresBusinessCardsAndSuppressesTheirGenericTools()
            throws Exception {
        Fixture fixture = fixture();
        JsonObject talkTo = new JsonObject();
        talkTo.addProperty("targetTeamMemberId", "member-2");
        talkTo.addProperty("content", "hello");
        talkTo.addProperty("delivery", "DELIVERED");
        fixture.current.get().getHistoryManager().addEventMessage(
                "TALK_TO_SEND", talkTo);
        JsonObject input = new JsonObject();
        input.addProperty("target", "member-2");
        input.addProperty("content", "hello");
        fixture.current.get().getHistoryManager().addToolMessage(
                "tool-talk", "mcp__cmd-proxy-runtime__talk_to",
                "completed", input, new JsonObject());
        JsonObject compaction = new JsonObject();
        compaction.addProperty("provider", "codex");
        fixture.current.get().getHistoryManager().addEventMessage(
                "COMPACTION_EVENT", compaction);

        Map<String, String> result = fixture.handler.handleGetSessionHistory(
                "rpc-history", one(basePayload()));

        JsonArray messages = json(result).getAsJsonArray("messages");
        assertEquals(2, messages.size());
        assertEquals("TALK_TO_SEND", messages.get(0).getAsJsonObject()
                .get("eventType").getAsString());
        assertEquals("COMPACTION_EVENT", messages.get(1).getAsJsonObject()
                .get("eventType").getAsString());
        assertEquals("TEAM_EVENT", messages.get(1).getAsJsonObject()
                .get("kind").getAsString());
        fixture.manager.close();
    }

    @Test
    public void busyMemberCancelSendsCurrentSessionCancel() throws Exception {
        Fixture fixture = fixture();
        fixture.current.get().setClientState(AbstractAcpClient.State.BUSY);
        fixture.manager.onMemberState(
                "team-1", "member-1", TeamMemberState.BUSY, null);

        Map<String, String> cancel = fixture.handler.handleCancel(
                "rpc-cancel-busy", one(basePayload()));

        assertEquals("true", cancel.get("accepted"));
        assertEquals("OK", cancel.get("code"));
        assertTrue(fixture.current.get().cancelled);
        assertEquals("session-member-1",
                json(cancel).get("sessionId").getAsString());
        fixture.manager.close();
    }

    @Test
    public void cancelRejectsStartingErrorAndClosedMemberStates() throws Exception {
        for (TeamMemberState state : Arrays.asList(
                TeamMemberState.STARTING, TeamMemberState.ERROR,
                TeamMemberState.CLOSING, TeamMemberState.CLOSED)) {
            Fixture fixture = fixture();
            fixture.current.get().setClientState(clientStateFor(state));
            fixture.manager.onMemberState("team-1", "member-1", state, null);

            Map<String, String> cancel = fixture.handler.handleCancel(
                    "rpc-cancel-" + state.name(), one(basePayload()));

            assertEquals(state.name(), "false", cancel.get("accepted"));
            assertFalse(state.name(), fixture.current.get().cancelled);
            assertEquals(state.name(),
                    state == TeamMemberState.STARTING
                            ? "TEAM_NOT_READY" : "CLIENT_CLOSED",
                    cancel.get("code"));
            fixture.manager.close();
        }
    }

    @Test
    public void memoryDreamUsesSourceRobotMemoryOwner() throws Exception {
        Fixture fixture = fixture();

        Map<String, String> result = fixture.handler.handleMemoryDream(
                "rpc-dream", one(basePayload()));

        assertEquals("true", result.get("accepted"));
        assertEquals("OK", result.get("code"));
        assertEquals("group-1", fixture.dreamRoute.get());
        assertTrue(json(result).get("triggered").getAsBoolean());
        assertEquals("group-1", json(result)
                .get("memoryOwnerSourceGroupId").getAsString());
        fixture.manager.close();
    }

    @Test
    public void newAndRestoreSessionReplaceOnlyTargetMemberAndPublishStates()
            throws Exception {
        Fixture fixture = fixture();

        Map<String, String> created =
                fixture.handler.handleNewSession("rpc-new", one(basePayload()));
        Map<String, String> restored = fixture.handler.handleRestoreSession(
                "rpc-restore", one(basePayload().replace(
                        "}", ",\"sessionId\":\"historic-session\"}")));

        assertEquals("OK", created.get("code"));
        assertEquals("new-session", JsonParser.parseString(created.get("data"))
                .getAsJsonObject().get("sessionId").getAsString());
        assertEquals("OK", restored.get("code"));
        assertEquals("historic-session", fixture.lastOptions.get()
                .getTargetRestoreSessionId());
        assertEquals("historic-session", JsonParser.parseString(restored.get("data"))
                .getAsJsonObject().get("sessionId").getAsString());
        assertEquals(1, fixture.registry.size());
        fixture.manager.close();
    }

    @Test
    public void automaticIdleRotationPublishesMemberSessionChanged() throws Exception {
        Fixture fixture = fixture();
        AutoNewSessionConfig config = new AutoNewSessionConfig();
        config.setEnabled(true);
        config.setCheckIntervalMinutes(1);
        config.setIdleMinutes(1);
        fixture.current.get().getRobotParam().setAutoNewSession(config);
        fixture.current.get().allowAutomaticRotation = true;

        assertEquals(0, fixture.manager.rotateIdleSessions(1L));
        assertEquals(1, fixture.manager.rotateIdleSessions(60_001L));

        TeamEventEnvelope changed = fixture.events.stream()
                .filter(event -> event.getType().name().equals("MEMBER_SESSION_CHANGED"))
                .findFirst().orElse(null);
        assertNotNull(changed);
        assertEquals("member-1", changed.getTeamMemberId());
        assertEquals("team-acp-member-1", changed.getAcpClientId());
        Map data = (Map) changed.getData();
        assertEquals("session-member-1", data.get("oldSessionId"));
        assertEquals("new-session", data.get("newSessionId"));
        assertEquals("AUTO_IDLE", data.get("reason"));
        fixture.manager.close();
    }

    @Test
    public void rejectsCrossOwnerMismatchedClientAndUnsafeFileNames() throws Exception {
        Fixture fixture = fixture();
        Map<String, String> owner = fixture.handler.handleGetStatus(
                "rpc-owner", one(basePayload().replace("owner-1", "owner-2")));
        Map<String, String> client = fixture.handler.handleGetStatus(
                "rpc-client", one(basePayload().replace(
                        "\"teamMemberId\":\"member-1\"",
                        "\"teamMemberId\":\"member-1\",\"acpClientId\":\"wrong\"")));
        String unsafe = basePayload().replace(
                "}", ",\"message\":\"hello\",\"files\":[{\"../a.txt\":\"aGk=\"}]}");
        Map<String, String> file =
                fixture.handler.handleSend("rpc-file", one(unsafe));

        assertEquals("UNAUTHORIZED", owner.get("code"));
        assertEquals("VALIDATION_ERROR", client.get("code"));
        assertEquals("VALIDATION_ERROR", file.get("code"));
        fixture.manager.close();
    }

    @Test
    public void teamScheduleReplacesOnlyMemberAndUsesNonRecursivePromptOptions()
            throws Exception {
        Fixture fixture = fixture();
        ScheduleOwnerKey owner = ScheduleOwnerKey.team(
                "owner-1", "team-1", "member-1", "Robot One");

        assertTrue(fixture.manager.executeScheduledPrompt(
                owner, "task-1", "[定时任务触发] hello"));

        TestClient replacement = fixture.current.get();
        assertEquals("[定时任务触发] hello", replacement.message);
        assertNotNull(replacement.promptOptions);
        assertTrue(replacement.promptOptions.isScheduleExecution());
        assertTrue(fixture.lastOptions.get().isForceNewSession());
        assertEquals(TeamMemberState.BUSY,
                fixture.manager.getRuntime("team-1").get().getDefinition()
                        .getMembers().get(0).getState());
        assertTrue(fixture.events.stream().anyMatch(event ->
                event.getType().name().equals("SCHEDULE_EVENT")));
        fixture.manager.close();
    }

    @Test
    public void teamScheduleGroupReusesCurrentSessionOrRestoresBoundSession()
            throws Exception {
        Fixture fixture = fixture();
        ScheduleOwnerKey owner = ScheduleOwnerKey.team(
                "owner-1", "team-1", "member-1", "Robot One");
        TestClient original = fixture.current.get();

        fixture.scheduleTaskManager.bindGroupSession(
                owner, "daily", original.getSessionId());
        assertTrue(fixture.manager.executeScheduledPrompt(
                owner, "task-1", "daily", "same-session"));
        assertSame(original, fixture.current.get());
        assertEquals("same-session", original.message);
        assertNull(fixture.lastOptions.get());

        original.setClientState(AbstractAcpClient.State.READY);
        fixture.manager.onMemberState(
                "team-1", "member-1", TeamMemberState.READY, null);
        fixture.scheduleTaskManager.bindGroupSession(
                owner, "other", "restored-session");
        assertTrue(fixture.manager.executeScheduledPrompt(
                owner, "task-2", "other", "restored"));
        assertEquals("restored-session",
                fixture.lastOptions.get().getTargetRestoreSessionId());
        assertEquals("restored", fixture.current.get().message);
        fixture.manager.close();
    }

    private Fixture fixture() throws Exception {
        TeamStore store = new TeamStore(temporaryFolder.newFolder().toPath());
        Map<String, AcpRobotParam> robots = new HashMap<>();
        robots.put("group-1", robot());
        MapTeamSourceRobotResolver resolver = new MapTeamSourceRobotResolver(robots);
        String fingerprint = resolver.snapshot(new TeamMemberCreateSpec(
                "member-1", "acp-Robot_One", "group-1", 0)).getConfigFingerprint();
        TeamMemberDefinition starting = new TeamMemberDefinition(
                "member-1", "acp-Robot_One", "group-1", "Robot One",
                "Robot One", "", 0, "remark", fingerprint);
        TeamDefinition creating = TeamDefinition.creating(
                "team-1", "owner-1", "Team", "team-acp-instance",
                "request-1", Arrays.asList(starting,
                new TeamMemberDefinition("member-2", "source-2", "Robot Two",
                        "Robot Two", "remark", "fingerprint-2")), 1L);
        List<TeamMemberDefinition> readyMembers = new ArrayList<>();
        for (TeamMemberDefinition member : creating.getMembers()) {
            readyMembers.add(member.withState(
                    TeamMemberState.READY, "session-" + member.getTeamMemberId(), null));
        }
        TeamDefinition ready = creating.transitionWithMembers(
                TeamState.READY, readyMembers, null, 2L);
        store.saveTeam(creating);
        store.saveTeam(ready);

        TeamClientRegistry registry = new TeamClientRegistry();
        AtomicReference<TestClient> current = new AtomicReference<>(
                client(ready, readyMembers.get(0), "session-member-1"));
        registry.register("team-1", "member-1", current.get());
        AtomicReference<TeamMemberStartOptions> lastOptions = new AtomicReference<>();
        TeamStartupCoordinator coordinator = new TeamStartupCoordinator(
                resolver, (runtime, member, snapshot, options, created) -> {
            lastOptions.set(options);
            String sessionId = options.getTargetRestoreSessionId() == null
                    ? "new-session" : options.getTargetRestoreSessionId();
            TestClient replacement = client(
                    runtime.getDefinition(), member, sessionId);
            created.accept(replacement);
            current.set(replacement);
            return replacement;
        }, registry, 1, 1_000L);
        List<TeamEventEnvelope> events = new ArrayList<>();
        TeamManager manager = new TeamManager(
                store, registry, resolver, events::add, coordinator);
        ScheduleTaskManager scheduleTaskManager = new ScheduleTaskManager(
                temporaryFolder.newFolder().toPath());
        manager.setScheduleTaskManager(scheduleTaskManager);
        AtomicReference<String> dreamRoute = new AtomicReference<>();
        manager.setMemoryDreamTrigger((sourceGroupId, workspacePath) -> {
            dreamRoute.set(sourceGroupId);
            return true;
        });
        assertTrue(manager.attachPersistedDefinition(ready));
        return new Fixture(manager, registry, new TeamCommandHandler(
                manager, "team-acp-instance"), current, lastOptions, events,
                dreamRoute, scheduleTaskManager);
    }

    private static TestClient client(TeamDefinition team, TeamMemberDefinition member,
                                     String sessionId) {
        AcpRobotParam robot = robot();
        AcpClientIdentity identity = AcpClientIdentity.team(
                member.getAcpClientId(), team.getTransportGroup(),
                "team/team-1/" + member.getTeamMemberId(), team.getOwnerChatterId(),
                team.getTeamId(), member.getTeamMemberId(), member.getSourceRobotName());
        return new TestClient(identity, robot, sessionId);
    }

    private static AcpRobotParam robot() {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("Robot One");
        return robot;
    }

    private static String basePayload() {
        return "{\"schemaVersion\":\"1\",\"ownerChatterId\":\"owner-1\","
                + "\"teamId\":\"team-1\",\"teamMemberId\":\"member-1\"}";
    }

    private static String[] one(String value) {
        return new String[]{value};
    }

    private static JsonObject json(Map<String, String> result) {
        return JsonParser.parseString(result.get("data")).getAsJsonObject();
    }

    private static AbstractAcpClient.State clientStateFor(TeamMemberState state) {
        switch (state) {
            case STARTING: return AbstractAcpClient.State.STARTING;
            case ERROR: return AbstractAcpClient.State.ERROR;
            case CLOSING: return AbstractAcpClient.State.CLOSING;
            case CLOSED: return AbstractAcpClient.State.CLOSED;
            default: throw new IllegalArgumentException(state.name());
        }
    }

    private static final class TestClient extends AcpClient {
        private String message;
        private List<Map<String, String>> files;
        private boolean cancelled;
        private PromptOptions promptOptions;
        private boolean allowAutomaticRotation;

        private TestClient(AcpClientIdentity identity, AcpRobotParam robot,
                           String sessionId) {
            super(".", identity, robot);
            state.set(AbstractAcpClient.State.READY);
            setSessionId(sessionId);
            contextUsagePercentage = 42.5;
        }

        @Override
        public void send(String input, List<Map<String, String>> attachments) {
            this.message = input;
            this.files = attachments;
        }

        @Override
        public void send(String input, List<Map<String, String>> attachments,
                         PromptOptions options) {
            this.message = input;
            this.files = attachments;
            this.promptOptions = options;
        }

        @Override
        public void cancel() {
            this.cancelled = true;
        }

        @Override
        public boolean tryReserveIdleForAutoNewSession(long nowMillis, long idleMillis) {
            return allowAutomaticRotation
                    && state.compareAndSet(State.READY, State.STARTING);
        }

        private void setClientState(AbstractAcpClient.State update) {
            state.set(update);
        }

        @Override
        public void close() throws IOException {
            state.set(AbstractAcpClient.State.CLOSED);
        }
    }

    private static final class Fixture {
        private final TeamManager manager;
        private final TeamClientRegistry registry;
        private final TeamCommandHandler handler;
        private final AtomicReference<TestClient> current;
        private final AtomicReference<TeamMemberStartOptions> lastOptions;
        private final List<TeamEventEnvelope> events;
        private final AtomicReference<String> dreamRoute;
        private final ScheduleTaskManager scheduleTaskManager;

        private Fixture(TeamManager manager, TeamClientRegistry registry,
                        TeamCommandHandler handler, AtomicReference<TestClient> current,
                        AtomicReference<TeamMemberStartOptions> lastOptions,
                        List<TeamEventEnvelope> events,
                        AtomicReference<String> dreamRoute,
                        ScheduleTaskManager scheduleTaskManager) {
            this.manager = manager;
            this.registry = registry;
            this.handler = handler;
            this.current = current;
            this.lastOptions = lastOptions;
            this.events = events;
            this.dreamRoute = dreamRoute;
            this.scheduleTaskManager = scheduleTaskManager;
        }
    }
}
