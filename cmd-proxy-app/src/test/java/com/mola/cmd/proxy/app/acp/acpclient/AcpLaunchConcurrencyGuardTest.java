package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.agent.KiroCliAgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.agent.OpenCodeAgentProvider;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AcpLaunchConcurrencyGuardTest {

    @Test
    public void openCodeSerializesCompleteStartupForEquivalentWorkspacePaths()
            throws Exception {
        Path workspace = Files.createTempDirectory("opencode-launch-serial");
        Path alias = workspace.resolve("child").resolve("..");
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        TestClient first = new TestClient(new OpenCodeAgentProvider(), workspace.toString(),
                () -> { },
                () -> holdStartup(firstEntered, releaseFirst, active, maxActive));
        TestClient second = new TestClient(new OpenCodeAgentProvider(), alias.toString(),
                () -> markStartup(secondEntered, active, maxActive));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstFuture = executor.submit(() -> startUnchecked(first));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            Future<?> secondFuture = executor.submit(() -> startUnchecked(second));

            assertFalse("same-workspace OpenCode startup must wait",
                    secondEntered.await(150, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            firstFuture.get(1, TimeUnit.SECONDS);
            secondFuture.get(1, TimeUnit.SECONDS);

            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
            assertEquals(1, maxActive.get());
            assertEquals(AbstractAcpClient.State.READY, first.getState());
            assertEquals(AbstractAcpClient.State.READY, second.getState());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void openCodeKeepsDifferentWorkspacesParallel() throws Exception {
        assertSecondStartupCanEnter(
                new OpenCodeAgentProvider(), Files.createTempDirectory("opencode-launch-a"),
                new OpenCodeAgentProvider(), Files.createTempDirectory("opencode-launch-b"));
    }

    @Test
    public void otherProvidersRemainParallelInSameWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("kiro-launch-parallel");
        assertSecondStartupCanEnter(
                new KiroCliAgentProvider(), workspace,
                new KiroCliAgentProvider(), workspace);
    }

    @Test
    public void failedStartupReleasesOpenCodeWorkspace() throws Exception {
        Path workspace = Files.createTempDirectory("opencode-launch-failure");
        TestClient failed = new TestClient(new OpenCodeAgentProvider(), workspace.toString(),
                () -> { throw new IOException("boom"); });
        try {
            failed.start();
            fail("startup should fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("boom"));
        }

        AtomicBoolean started = new AtomicBoolean();
        TestClient retry = new TestClient(new OpenCodeAgentProvider(), workspace.toString(),
                () -> started.set(true));
        retry.start();

        assertTrue(started.get());
        assertEquals(AbstractAcpClient.State.ERROR, failed.getState());
        assertEquals(AbstractAcpClient.State.READY, retry.getState());
    }

    @Test
    public void interruptedWaiterDoesNotStartLater() throws Exception {
        Path workspace = Files.createTempDirectory("opencode-launch-interrupt");
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean secondStarted = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean failedWithInterruption = new AtomicBoolean();
        CountDownLatch secondAttempting = new CountDownLatch(1);
        CountDownLatch interruptionObserved = new CountDownLatch(1);

        TestClient first = new TestClient(new OpenCodeAgentProvider(), workspace.toString(),
                () -> {
                    firstEntered.countDown();
                    await(releaseFirst);
                });
        TestClient second = new TestClient(new OpenCodeAgentProvider(), workspace.toString(),
                () -> secondStarted.set(true));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstFuture = executor.submit(() -> startUnchecked(first));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            Future<?> secondFuture = executor.submit(() -> {
                secondAttempting.countDown();
                try {
                    second.start();
                } catch (IOException e) {
                    failedWithInterruption.set(e.getMessage().contains("启动锁时被中断"));
                    interrupted.set(Thread.currentThread().isInterrupted());
                    interruptionObserved.countDown();
                }
            });
            assertTrue(secondAttempting.await(1, TimeUnit.SECONDS));
            secondFuture.cancel(true);
            releaseFirst.countDown();
            firstFuture.get(1, TimeUnit.SECONDS);

            // cancel(true) may mark the Future complete before the task observes interruption.
            assertTrue(interruptionObserved.await(1, TimeUnit.SECONDS));
            assertTrue(failedWithInterruption.get());
            assertTrue(interrupted.get());
            assertFalse(secondStarted.get());
            assertEquals(AbstractAcpClient.State.ERROR, second.getState());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private static void assertSecondStartupCanEnter(AgentProvider firstProvider,
                                                     Path firstWorkspace,
                                                     AgentProvider secondProvider,
                                                     Path secondWorkspace) throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        TestClient first = new TestClient(firstProvider, firstWorkspace.toString(), () -> {
            firstEntered.countDown();
            await(releaseFirst);
        });
        TestClient second = new TestClient(secondProvider, secondWorkspace.toString(),
                secondEntered::countDown);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstFuture = executor.submit(() -> startUnchecked(first));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            Future<?> secondFuture = executor.submit(() -> startUnchecked(second));
            assertTrue("independent startup should remain parallel",
                    secondEntered.await(1, TimeUnit.SECONDS));
            secondFuture.get(1, TimeUnit.SECONDS);
            releaseFirst.countDown();
            firstFuture.get(1, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private static void holdStartup(CountDownLatch entered, CountDownLatch release,
                                    AtomicInteger active, AtomicInteger maxActive)
            throws IOException {
        int current = active.incrementAndGet();
        maxActive.accumulateAndGet(current, Math::max);
        entered.countDown();
        try {
            await(release);
        } finally {
            active.decrementAndGet();
        }
    }

    private static void markStartup(CountDownLatch entered, AtomicInteger active,
                                    AtomicInteger maxActive) {
        int current = active.incrementAndGet();
        maxActive.accumulateAndGet(current, Math::max);
        entered.countDown();
        active.decrementAndGet();
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("test interrupted", e);
        }
    }

    private static void startUnchecked(TestClient client) {
        try {
            client.start();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @FunctionalInterface
    private interface StartupAction {
        void run() throws IOException;
    }

    private static final class TestClient extends AbstractAcpClient {
        private final StartupAction processAction;
        private final StartupAction sessionAction;

        private TestClient(AgentProvider provider, String workspace,
                           StartupAction processAction) {
            this(provider, workspace, processAction, () -> { });
        }

        private TestClient(AgentProvider provider, String workspace,
                           StartupAction processAction, StartupAction sessionAction) {
            super(provider, workspace, "test-" + System.nanoTime());
            this.processAction = processAction;
            this.sessionAction = sessionAction;
        }

        @Override
        protected void startProcess() throws IOException {
            processAction.run();
        }

        @Override
        protected void initialize() {
        }

        @Override
        protected void createSession() throws IOException {
            sessionAction.run();
            sessionId = "test-session";
        }
    }
}
