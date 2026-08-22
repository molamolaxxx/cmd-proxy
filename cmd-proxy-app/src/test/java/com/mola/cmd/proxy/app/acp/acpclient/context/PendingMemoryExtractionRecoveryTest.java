package com.mola.cmd.proxy.app.acp.acpclient.context;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.acpclient.MemoryManagerBridge;
import com.mola.cmd.proxy.app.acp.memory.PendingMemoryExtractionRecovery;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class PendingMemoryExtractionRecoveryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void successfulRecoveryClearsPendingAndIncrementsSessionCount()
            throws Exception {
        ConversationHistoryManager history = history("success");
        FakeMemoryManager memory = new FakeMemoryManager(true);
        ConcurrentMap<String, String> claims = new ConcurrentHashMap<>();

        int submitted = PendingMemoryExtractionRecovery.recover(
                history, "Robot One", "/workspace", memory, claims);

        assertEquals(1, submitted);
        assertEquals(2, memory.messages);
        assertEquals(1, memory.sessionCount);
        assertTrue(history.listPendingMemoryExtractions().isEmpty());
        assertTrue(claims.isEmpty());
    }

    @Test
    public void failedRecoveryKeepsPendingForNextStartup() throws Exception {
        ConversationHistoryManager history = history("failure");
        FakeMemoryManager memory = new FakeMemoryManager(false);
        ConcurrentMap<String, String> claims = new ConcurrentHashMap<>();

        int submitted = PendingMemoryExtractionRecovery.recover(
                history, "Robot One", "/workspace", memory, claims);

        assertEquals(1, submitted);
        assertEquals(0, memory.sessionCount);
        assertEquals(1, history.listPendingMemoryExtractions().size());
        assertTrue(claims.isEmpty());
    }

    @Test
    public void staleGenerationCallbackCannotReleaseNewGenerationClaim()
            throws Exception {
        ConversationHistoryManager history = history("generation");
        ConcurrentMap<String, String> claims = new ConcurrentHashMap<>();
        DeferredMemoryManager oldGeneration = new DeferredMemoryManager();
        DeferredMemoryManager newGeneration = new DeferredMemoryManager();

        assertEquals(1, PendingMemoryExtractionRecovery.recover(
                history, "Robot One", "/workspace", oldGeneration, claims));
        claims.clear();
        assertEquals(1, PendingMemoryExtractionRecovery.recover(
                history, "Robot One", "/workspace", newGeneration, claims));
        String newToken = claims.get("Robot One:session-1");

        oldGeneration.fail();

        assertEquals(newToken, claims.get("Robot One:session-1"));
        newGeneration.succeed();
        assertTrue(claims.isEmpty());
        assertTrue(history.listPendingMemoryExtractions().isEmpty());
    }

    @Test
    public void restoredSessionWithoutPendingSeedsLoadedHistoryBaseline()
            throws Exception {
        ConversationHistoryManager history = new ConversationHistoryManager(
                AcpClientIdentity.main("group-1", "Robot One", "Robot One"),
                temporaryFolder.newFolder("resume-baseline").toPath());
        FakeMemoryManager memory = new FakeMemoryManager(true);

        int submitted = PendingMemoryExtractionRecovery.recover(
                history, "Robot One", "/workspace", memory,
                new ConcurrentHashMap<>(), "restored-session", 6);

        assertEquals(0, submitted);
        assertEquals("restored-session", memory.resumedSessionId);
        assertEquals(6, memory.resumedHistorySize);
        assertEquals(0, memory.activeRecoveries);
    }

    @Test
    public void restoredSessionWithPendingUsesActiveRecoveryWithoutPreseeding()
            throws Exception {
        ConversationHistoryManager history = history("active-recovery");
        FakeMemoryManager memory = new FakeMemoryManager(true);

        int submitted = PendingMemoryExtractionRecovery.recover(
                history, "Robot One", "/workspace", memory,
                new ConcurrentHashMap<>(), "session-1", 2);

        assertEquals(1, submitted);
        assertEquals(1, memory.activeRecoveries);
        assertNull(memory.resumedSessionId);
        assertTrue(history.listPendingMemoryExtractions().isEmpty());
    }

    private ConversationHistoryManager history(String directory) throws Exception {
        ConversationHistoryManager history = new ConversationHistoryManager(
                AcpClientIdentity.main("group-1", "Robot One", "Robot One"),
                temporaryFolder.newFolder(directory).toPath());
        history.addUserMessage("remember this");
        history.addAssistantMessage("done");
        history.flushTurn("session-1");
        assertTrue(history.markMemoryExtractionPending("session-1", 1));
        return history;
    }

    private static final class FakeMemoryManager implements MemoryManagerBridge {
        private final boolean succeed;
        private int messages;
        private int sessionCount;
        private int activeRecoveries;
        private String resumedSessionId;
        private int resumedHistorySize;

        private FakeMemoryManager(boolean succeed) {
            this.succeed = succeed;
        }

        @Override
        public String buildMemoryPrompt(String workspacePath) {
            return "";
        }

        @Override
        public void submitExtract(String sourceSessionId, String workspacePath,
                                  List<ContextMessage> history) {
        }

        @Override
        public void resumeSession(String sourceSessionId, int historySize) {
            resumedSessionId = sourceSessionId;
            resumedHistorySize = historySize;
        }

        @Override
        public void submitRecoverActive(String sourceSessionId,
                                        String workspacePath,
                                        List<ContextMessage> history,
                                        Runnable onSuccess,
                                        Consumer<Throwable> onFailure) {
            activeRecoveries++;
            submitExtractFull(sourceSessionId, workspacePath, history,
                    onSuccess, onFailure);
        }

        @Override
        public void submitExtractFull(String sourceSessionId, String workspacePath,
                                      List<ContextMessage> history,
                                      Runnable onSuccess,
                                      Consumer<Throwable> onFailure) {
            messages = history.size();
            if (succeed) {
                onSuccess.run();
            } else {
                onFailure.accept(new IllegalStateException("failed"));
            }
        }

        @Override
        public void incrementSessionCount(String workspacePath) {
            sessionCount++;
        }

        @Override
        public void onMemoryAccessed(String workspacePath, String filePath) {
        }
    }

    private static final class DeferredMemoryManager implements MemoryManagerBridge {
        private Runnable onSuccess;
        private Consumer<Throwable> onFailure;

        @Override
        public String buildMemoryPrompt(String workspacePath) {
            return "";
        }

        @Override
        public void submitExtract(String sourceSessionId, String workspacePath,
                                  List<ContextMessage> history) {
        }

        @Override
        public void submitExtractFull(String sourceSessionId, String workspacePath,
                                      List<ContextMessage> history,
                                      Runnable onSuccess,
                                      Consumer<Throwable> onFailure) {
            this.onSuccess = onSuccess;
            this.onFailure = onFailure;
        }

        @Override
        public void incrementSessionCount(String workspacePath) {
        }

        @Override
        public void onMemoryAccessed(String workspacePath, String filePath) {
        }

        private void succeed() {
            onSuccess.run();
        }

        private void fail() {
            onFailure.accept(new IllegalStateException("stale"));
        }
    }
}
