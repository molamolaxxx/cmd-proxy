package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry;
import com.mola.cmd.proxy.app.acp.acpclient.MainSessionApplicationService;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class StarweaveSessionManagerTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void lifecyclePreservesGenerationAndDeleteForcesNewOpen() throws Exception {
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = registry(factory);
        StarweaveSessionManager manager = manager(registry);

        JSONObject opened = manager.open("Robot");
        String groupId = opened.getString("groupId");
        String firstSession = opened.getString("sessionId");
        long firstGeneration = opened.getLongValue("generation");
        assertEquals("STARWEAVE",
                opened.getJSONObject("address").getString("surface"));

        JSONObject replaced = manager.newSession(
                groupId, firstSession, firstGeneration);
        assertNotEquals(firstSession, replaced.getString("sessionId"));
        assertEquals(firstGeneration + 1L, replaced.getLongValue("generation"));

        JSONObject restored = manager.restore(groupId, "provider-history-session",
                replaced.getString("sessionId"), replaced.getLongValue("generation"));
        assertEquals("provider-history-session", restored.getString("sessionId"));
        assertEquals(replaced.getLongValue("generation") + 1L,
                restored.getLongValue("generation"));

        JSONObject deleted = manager.delete(groupId, restored.getString("sessionId"),
                restored.getLongValue("generation"));
        assertFalse(deleted.getBooleanValue("active"));
        assertTrue(deleted.getBooleanValue("forceNewOnNextOpen"));
        assertNull(registry.getClient(groupId));
        assertTrue(manager.list().isEmpty());

        JSONObject reopened = manager.open("Robot");
        assertNotEquals("provider-history-session", reopened.getString("sessionId"));
        assertTrue(factory.lastCreated.forceNew);
        assertTrue(reopened.getLongValue("generation") > deleted.getLongValue("generation"));
        assertEquals(1, manager.list().size());
        registry.closeAllForShutdown();
    }

    @Test
    public void failedNewSessionLeavesManagerAndRegistryOnPreviousGeneration()
            throws Exception {
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = registry(factory);
        StarweaveSessionManager manager = manager(registry);
        JSONObject opened = manager.open("Robot");
        AcpClient original = registry.getClient(opened.getString("groupId"));
        factory.failNextStart = true;

        try {
            manager.newSession(opened.getString("groupId"),
                    opened.getString("sessionId"), opened.getLongValue("generation"));
            fail("new session should fail");
        } catch (IOException expected) {
            assertEquals("planned start failure", expected.getMessage());
        }

        JSONObject current = manager.status(opened.getString("groupId"));
        assertSame(original, registry.getClient(opened.getString("groupId")));
        assertEquals(opened.getString("sessionId"), current.getString("sessionId"));
        assertEquals(opened.getLongValue("generation"), current.getLongValue("generation"));
        assertFalse(((FakeClient) original).closed);
        registry.closeAllForShutdown();
    }

    @Test
    public void sharedLifecycleReplacementReinstallsProjectionAndPublishesLocally()
            throws Exception {
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = registry(factory);
        StarweaveSessionManager manager = manager(registry);
        JSONObject opened = manager.open("Robot");
        String groupId = opened.getString("groupId");
        AcpClient original = registry.getClient(groupId);
        MainSessionApplicationService service =
                new MainSessionApplicationService(registry);

        AcpClient replacement = service.replaceIfCurrent(groupId, original, null,
                client -> manager.initializeReplacement(
                        groupId, client, client.getRobotParam()));
        manager.onSessionReplaced(groupId, original.getSessionId(),
                replacement.getSessionId(), "SCHEDULE");

        JSONObject current = manager.status(groupId);
        assertEquals(replacement.getSessionId(), current.getString("sessionId"));
        assertEquals(opened.getLongValue("generation") + 1L,
                current.getLongValue("generation"));
        JSONObject batch = manager.eventBatch(groupId, replacement.getSessionId(),
                0L, current.getLong("generation"));
        assertEquals("SESSION_REPLACED", batch.getJSONArray("events")
                .getJSONObject(0).getString("type"));
        assertEquals("SCHEDULE", batch.getJSONArray("events")
                .getJSONObject(0).getJSONObject("payload").getString("reason"));
        registry.closeAllForShutdown();
    }

    @Test
    public void stagedUploadIsDeliveredToAcpSendAsSessionBoundFileContent()
            throws Exception {
        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = registry(factory);
        StarweaveSessionIndex index = new StarweaveSessionIndex(
                temporary.newFolder("upload-index").toPath().resolve("sessions.json"));
        StarweaveUploadStore uploadStore = new StarweaveUploadStore(
                temporary.newFolder("uploads").toPath());
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("Robot");
        robot.setWorkDir(temporary.getRoot().getAbsolutePath());
        StarweaveSessionManager manager = new StarweaveSessionManager(
                "instance-test", registry, name -> robot,
                (groupId, client, configuredRobot) -> { }, index,
                new StarweaveSessionEventStore(64), uploadStore);

        JSONObject opened = manager.open("Robot");
        byte[] content = "attachment reaches ACP".getBytes(StandardCharsets.UTF_8);
        JSONObject upload = manager.upload(opened.getString("groupId"),
                opened.getString("sessionId"), opened.getLongValue("generation"),
                "proof.txt", Base64.getEncoder().encodeToString(content));

        JSONObject sent = manager.send(opened.getString("groupId"), "inspect attachment",
                opened.getString("sessionId"), opened.getLongValue("generation"),
                "REJECT", java.util.Collections.singletonList(
                        upload.getString("uploadId")));

        assertTrue(sent.getBooleanValue("accepted"));
        assertEquals("inspect attachment", factory.lastCreated.sentMessage);
        assertNotNull(factory.lastCreated.sentFiles);
        assertEquals(1, factory.lastCreated.sentFiles.size());
        String encoded = factory.lastCreated.sentFiles.get(0).get("proof.txt");
        assertArrayEquals(content, Base64.getDecoder().decode(encoded));
        registry.closeAllForShutdown();
    }

    @Test
    public void restartRecoversEveryActiveClosedSessionAndSkipsDeletedSlots()
            throws Exception {
        StarweaveSessionIndex index = new StarweaveSessionIndex(
                temporary.newFolder("recovery-index").toPath().resolve("sessions.json"));
        String robotGroup = StarweaveIdentity.identity(
                "instance-test", "Robot").getLogicalId();
        String secondGroup = StarweaveIdentity.identity(
                "instance-test", "Second").getLogicalId();
        String deletedGroup = StarweaveIdentity.identity(
                "instance-test", "Deleted").getLogicalId();
        index.activate("Robot", robotGroup, "persisted-robot-session");
        index.activate("Second", secondGroup, "persisted-second-session");
        index.activate("Deleted", deletedGroup, "deleted-session");
        index.markDeleted(deletedGroup);

        FakeFactory factory = new FakeFactory();
        AcpClientRegistry registry = registry(factory);
        StarweaveSessionManager manager = new StarweaveSessionManager(
                "instance-test", registry, name -> {
                    AcpRobotParam robot = new AcpRobotParam();
                    robot.setName(name);
                    robot.setWorkDir(temporary.getRoot().getAbsolutePath());
                    return robot;
                }, (groupId, client, robot) -> { }, index,
                new StarweaveSessionEventStore(64));

        JSONObject recovery = manager.recoverActiveSessions();

        assertEquals(2, recovery.getIntValue("attempted"));
        assertEquals(2, recovery.getIntValue("recoveredCount"));
        assertEquals(0, recovery.getIntValue("failedCount"));
        assertNotNull(registry.getClient(robotGroup));
        assertNotNull(registry.getClient(secondGroup));
        assertNull(registry.getClient(deletedGroup));
        assertEquals(2, factory.sequence.get());
        registry.closeAllForShutdown();
    }

    private StarweaveSessionManager manager(AcpClientRegistry registry) throws Exception {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("Robot");
        robot.setWorkDir(temporary.getRoot().getAbsolutePath());
        return new StarweaveSessionManager("instance-test", registry,
                name -> "Robot".equals(name) ? robot : null,
                (groupId, client, configuredRobot) -> { },
                new StarweaveSessionIndex(temporary.newFolder("index")
                        .toPath().resolve("sessions.json")),
                new StarweaveSessionEventStore(64));
    }

    private static AcpClientRegistry registry(FakeFactory handler) throws Exception {
        Class<?> factoryType = Class.forName(
                "com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry$ClientFactory");
        Object factory = Proxy.newProxyInstance(factoryType.getClassLoader(),
                new Class<?>[]{factoryType}, handler);
        Constructor<AcpClientRegistry> constructor =
                AcpClientRegistry.class.getDeclaredConstructor(factoryType);
        constructor.setAccessible(true);
        return constructor.newInstance(factory);
    }

    private static final class FakeFactory implements InvocationHandler {
        private final AtomicInteger sequence = new AtomicInteger();
        private volatile boolean failNextStart;
        private volatile FakeClient lastCreated;

        @Override
        public synchronized Object invoke(Object proxy, Method method, Object[] args) {
            if (!"create".equals(method.getName())) {
                if ("toString".equals(method.getName())) return "FakeFactory";
                throw new UnsupportedOperationException(method.getName());
            }
            AcpClientIdentity identity;
            if (args[1] instanceof AcpClientIdentity) {
                identity = (AcpClientIdentity) args[1];
            } else {
                AcpRobotParam robot = (AcpRobotParam) args[2];
                identity = AcpClientIdentity.main(
                        (String) args[1], (String) args[1], robot.getName());
            }
            lastCreated = new FakeClient(identity, (AcpRobotParam) args[2],
                    sequence.incrementAndGet(), failNextStart);
            failNextStart = false;
            return lastCreated;
        }
    }

    private static final class FakeClient extends AcpClient {
        private final int sequence;
        private final boolean failStart;
        private boolean forceNew;
        private String restoreSessionId;
        private boolean closed;
        private String sentMessage;
        private List<Map<String, String>> sentFiles;

        private FakeClient(AcpClientIdentity identity, AcpRobotParam robot,
                           int sequence, boolean failStart) {
            super(".", identity, robot);
            this.sequence = sequence;
            this.failStart = failStart;
        }

        @Override
        public void setForceNewSession(boolean forceNewSession) {
            super.setForceNewSession(forceNewSession);
            this.forceNew = forceNewSession;
        }

        @Override
        public void setTargetRestoreSessionId(String targetRestoreSessionId) {
            super.setTargetRestoreSessionId(targetRestoreSessionId);
            this.restoreSessionId = targetRestoreSessionId;
        }

        @Override
        public void start() throws IOException {
            if (failStart) throw new IOException("planned start failure");
            setSessionId(restoreSessionId == null
                    ? "session-" + sequence : restoreSessionId);
            state.set(State.READY);
        }

        @Override
        public void close() {
            closed = true;
            state.set(State.CLOSED);
        }

        @Override
        public void closeForShutdown() {
            close();
        }

        @Override
        public void send(String userInput, List<Map<String, String>> files) {
            sentMessage = userInput;
            sentFiles = files;
            state.set(State.BUSY);
        }
    }
}
