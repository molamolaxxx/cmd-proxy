package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.acpclient.agent.KiroCliAgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.agent.DeepSeekHarnessAcpProvider;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import org.junit.Test;

import java.io.*;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class AbstractAcpClientShutdownTest {

    @Test
    public void closeSendsSessionEndAndAllowsGracefulExit() throws Exception {
        FakeProcess process = FakeProcess.graceful();
        TestClient client = new TestClient();
        StringWriter output = client.attach(process, "session-1");

        client.close();

        assertTrue(output.toString().contains("\"method\":\"session/end\""));
        assertTrue(output.toString().contains("\"sessionId\":\"session-1\""));
        assertFalse(process.destroyCalled);
        assertFalse(process.forceCalled);
        assertEquals(AbstractAcpClient.State.CLOSED, client.getState());
    }

    @Test
    public void closeFallsBackToDestroyAfterGracefulTimeout() throws Exception {
        FakeProcess process = FakeProcess.exitsOnDestroy();
        TestClient client = new TestClient();
        client.attach(process, "session-2");

        client.close();

        assertTrue(process.destroyCalled);
        assertFalse(process.forceCalled);
        assertEquals(AbstractAcpClient.State.CLOSED, client.getState());
    }

    @Test
    public void closeUsesDestroyForciblyAsFinalFallback() throws Exception {
        FakeProcess process = FakeProcess.exitsOnForce();
        TestClient client = new TestClient();
        client.attach(process, "session-3");

        client.close();

        assertTrue(process.destroyCalled);
        assertTrue(process.forceCalled);
        assertEquals(AbstractAcpClient.State.CLOSED, client.getState());
    }

    @Test
    public void closeWithoutSessionSkipsProtocolAndDestroysProcess() throws Exception {
        FakeProcess process = FakeProcess.exitsOnDestroy();
        TestClient client = new TestClient();
        StringWriter output = client.attach(process, null);

        client.close();

        assertFalse(output.toString().contains("session/end"));
        assertTrue(process.destroyCalled);
    }

    @Test
    public void closeBusySessionCancelsBeforeEndingSession() throws Exception {
        FakeProcess process = FakeProcess.graceful();
        TestClient client = new TestClient();
        StringWriter output = client.attach(process, "session-busy", AbstractAcpClient.State.BUSY);

        client.close();

        String protocol = output.toString();
        int cancelIndex = protocol.indexOf("\"method\":\"session/cancel\"");
        int endIndex = protocol.indexOf("\"method\":\"session/end\"");
        assertTrue(cancelIndex >= 0);
        assertTrue(endIndex > cancelIndex);
    }

    @Test
    public void deepSeekHarnessUsesSessionCloseInsteadOfSessionEnd() throws Exception {
        FakeProcess process = FakeProcess.graceful();
        TestClient client = new TestClient(new DeepSeekHarnessAcpProvider());
        StringWriter output = client.attach(process, "dsh-session");

        client.close();

        assertTrue(output.toString().contains("\"method\":\"session/close\""));
        assertFalse(output.toString().contains("session/end"));
        assertFalse(process.destroyCalled);
    }

    private static final class TestClient extends AbstractAcpClient {
        private TestClient() {
            this(new KiroCliAgentProvider());
        }

        private TestClient(AgentProvider provider) {
            super(provider, ".",
                    AcpClientIdentity.main("group-1", "Robot One", "Robot One"), null);
        }

        private StringWriter attach(Process process, String sessionId) {
            return attach(process, sessionId, State.READY);
        }

        private StringWriter attach(Process process, String sessionId, State initialState) {
            StringWriter output = new StringWriter();
            this.process = process;
            this.writer = new BufferedWriter(output);
            this.reader = new BufferedReader(new StringReader(""));
            this.sessionId = sessionId;
            this.state.set(initialState);
            return output;
        }

        @Override
        protected void createSession() {
        }

        @Override
        protected long gracefulShutdownTimeoutMillis() {
            return 0L;
        }

        @Override
        protected long destroyShutdownTimeoutMillis() {
            return 0L;
        }

        @Override
        protected long forceShutdownTimeoutMillis() {
            return 0L;
        }
    }

    private static final class FakeProcess extends Process {
        private enum Mode {
            GRACEFUL,
            DESTROY,
            FORCE
        }

        private final Mode mode;
        private boolean alive = true;
        private boolean destroyCalled;
        private boolean forceCalled;

        private FakeProcess(Mode mode) {
            this.mode = mode;
        }

        static FakeProcess graceful() {
            return new FakeProcess(Mode.GRACEFUL);
        }

        static FakeProcess exitsOnDestroy() {
            return new FakeProcess(Mode.DESTROY);
        }

        static FakeProcess exitsOnForce() {
            return new FakeProcess(Mode.FORCE);
        }

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
            if (mode == Mode.GRACEFUL) {
                alive = false;
                return true;
            }
            return !alive;
        }

        @Override
        public int exitValue() {
            if (alive) throw new IllegalThreadStateException();
            return 0;
        }

        @Override
        public void destroy() {
            destroyCalled = true;
            if (mode == Mode.DESTROY) {
                alive = false;
            }
        }

        @Override
        public Process destroyForcibly() {
            forceCalled = true;
            alive = false;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
