package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.agent.KiroCliAgentProvider;
import org.junit.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AbstractAcpClientRuntimeLeaseTest {

    @Test
    public void releasesRuntimeLeaseOnlyAfterStartedProcessExits() throws Exception {
        LeaseAwareProvider provider = new LeaseAwareProvider(false);
        TestClient client = new TestClient(provider);

        client.start();

        assertTrue(provider.acquired.get());
        assertTrue(provider.released.await(2, TimeUnit.SECONDS));
    }

    @Test
    public void releasesPreparedLeaseWhenProcessCannotStart() throws Exception {
        LeaseAwareProvider provider = new LeaseAwareProvider(true);
        TestClient client = new TestClient(provider);

        try {
            client.start();
            fail("startup should fail");
        } catch (IOException expected) {
            assertTrue(provider.acquired.get());
            assertTrue(provider.released.await(1, TimeUnit.SECONDS));
        }
    }

    private static final class TestClient extends AbstractAcpClient {
        private TestClient(LeaseAwareProvider provider) {
            super(provider, ".", "runtime-lease-test");
        }

        @Override
        protected void initialize() {
        }

        @Override
        protected void createSession() {
            sessionId = "runtime-lease-session";
        }
    }

    private static final class LeaseAwareProvider extends KiroCliAgentProvider {
        private final boolean invalidCommand;
        private final AtomicBoolean acquired = new AtomicBoolean();
        private final CountDownLatch released = new CountDownLatch(1);

        private LeaseAwareProvider(boolean invalidCommand) {
            this.invalidCommand = invalidCommand;
        }

        @Override
        public RuntimeLease prepareRuntimeLaunch(AcpRobotParam robotParam,
                                                 Map<String, String> environment) {
            acquired.set(true);
            return released::countDown;
        }

        @Override
        public String getCommand(AcpRobotParam robotParam,
                                 Map<String, String> environment) {
            if (invalidCommand) {
                return "cmd-proxy-command-that-does-not-exist";
            }
            return isWindows() ? "cmd.exe" : "/bin/sh";
        }

        @Override
        public String[] getArgs(AcpRobotParam robotParam,
                                Map<String, String> environment) {
            return isWindows()
                    ? new String[]{"/c", "ping -n 2 127.0.0.1 >NUL"}
                    : new String[]{"-c", "sleep 0.2"};
        }

        private boolean isWindows() {
            return System.getProperty("os.name", "")
                    .toLowerCase(java.util.Locale.ROOT).contains("win");
        }
    }
}
