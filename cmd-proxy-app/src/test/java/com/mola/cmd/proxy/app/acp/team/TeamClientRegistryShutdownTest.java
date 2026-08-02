package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class TeamClientRegistryShutdownTest {

    @Test
    public void globalShutdownUsesDeferredMemoryClosePath() {
        TeamClientRegistry registry = new TeamClientRegistry();
        TrackingClient client = new TrackingClient();
        assertTrue(registry.register("team-1", "member-1", client));

        assertTrue(registry.closeAllForShutdown().isEmpty());

        assertTrue(client.shutdownClose);
        assertFalse(client.normalClose);
        assertEquals(0, registry.size());
    }

    private static final class TrackingClient extends AcpClient {
        private boolean normalClose;
        private boolean shutdownClose;

        private TrackingClient() {
            super(".", AcpClientIdentity.team(
                    "team-acp-member-1", "team-acp-instance",
                    "team/team-1/member-1", "owner-1",
                    "team-1", "member-1", "Robot One"), robot());
        }

        @Override
        public void close() throws IOException {
            normalClose = true;
        }

        @Override
        public void closeForShutdown() throws IOException {
            shutdownClose = true;
        }

        private static AcpRobotParam robot() {
            AcpRobotParam robot = new AcpRobotParam();
            robot.setName("Robot One");
            return robot;
        }
    }
}
