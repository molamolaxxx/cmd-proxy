package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.KiroCliAgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage;
import com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class AcpClientDeferredMemoryShutdownTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shutdownFlushesAndMarksPendingWithoutSubmittingExtraction()
            throws Exception {
        Fixture fixture = fixture("shutdown");
        fixture.client.getHistoryManager().setOnTurnFlushed(() ->
                fixture.memory.submitExtract("/workspace",
                        fixture.client.getConversationHistory()));

        fixture.client.closeForShutdown();

        assertEquals(0, fixture.memory.incrementalSubmissions);
        assertEquals(0, fixture.memory.fullSubmissions);
        assertEquals(1, fixture.client.getHistoryManager()
                .listPendingMemoryExtractions().size());
        assertEquals(AbstractAcpClient.State.CLOSED, fixture.client.getState());
    }

    @Test
    public void normalCloseSubmitsExtractionAndClearsPendingOnSuccess()
            throws Exception {
        Fixture fixture = fixture("normal");

        fixture.client.close();

        assertEquals(1, fixture.memory.fullSubmissions);
        assertEquals(1, fixture.memory.sessionCount);
        assertTrue(fixture.client.getHistoryManager()
                .listPendingMemoryExtractions().isEmpty());
    }

    private Fixture fixture(String directory) throws Exception {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("Robot One");
        AcpClientIdentity identity =
                AcpClientIdentity.main("group-1", "Robot One", "Robot One");
        ConversationHistoryManager history = new ConversationHistoryManager(
                identity, temporaryFolder.newFolder(directory).toPath());
        TestClient client = new TestClient(identity, robot, history);
        FakeMemoryManager memory = new FakeMemoryManager();
        client.setMemoryManager(memory);
        client.attach("session-1");
        history.addUserMessage("remember this");
        history.addAssistantMessage("done");
        return new Fixture(client, memory);
    }

    private static final class Fixture {
        private final TestClient client;
        private final FakeMemoryManager memory;

        private Fixture(TestClient client, FakeMemoryManager memory) {
            this.client = client;
            this.memory = memory;
        }
    }

    private static final class TestClient extends AcpClient {
        private TestClient(AcpClientIdentity identity, AcpRobotParam robot,
                           ConversationHistoryManager history) {
            super(new KiroCliAgentProvider(), ".", identity, robot, history);
        }

        private void attach(String sessionId) {
            this.process = new GracefulProcess();
            this.writer = new BufferedWriter(new StringWriter());
            this.reader = new BufferedReader(new StringReader(""));
            setSessionId(sessionId);
            this.state.set(State.READY);
        }
    }

    private static final class FakeMemoryManager implements MemoryManagerBridge {
        private int incrementalSubmissions;
        private int fullSubmissions;
        private int sessionCount;

        @Override
        public String buildMemoryPrompt(String workspacePath) {
            return "";
        }

        @Override
        public void submitExtract(String workspacePath, List<ContextMessage> history) {
            incrementalSubmissions++;
        }

        @Override
        public void submitExtractFull(String workspacePath,
                                      List<ContextMessage> history,
                                      Runnable onSuccess,
                                      Consumer<Throwable> onFailure) {
            fullSubmissions++;
            onSuccess.run();
        }

        @Override
        public void incrementSessionCount(String workspacePath) {
            sessionCount++;
        }

        @Override
        public void onMemoryAccessed(String workspacePath, String filePath) {
        }
    }

    private static final class GracefulProcess extends Process {
        private boolean alive = true;

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            alive = false;
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            alive = false;
            return true;
        }

        @Override
        public int exitValue() {
            if (alive) throw new IllegalThreadStateException();
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
