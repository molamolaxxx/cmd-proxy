package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import org.junit.Test;

import static org.junit.Assert.*;

public class TeamCapabilityParityTest {

    @Test
    public void teamClientUsesSameConcreteClientProviderConfigAndMcpResolution()
            throws Exception {
        AcpRobotParam source = new AcpRobotParam();
        source.setName("Robot");
        source.setWorkDir(".");
        source.setAgentProvider("CODEX_ACP");
        source.setModel("gpt-5");
        source.setApiKey("secret");
        source.setProxyEnabled(true);
        source.setHttpProxy("http://proxy");
        TeamSourceRobotSnapshot snapshot = TeamSourceRobotSnapshots.capture(
                "group-1", "acp-Robot", source);
        AcpRobotParam teamRobot = snapshot.copyRobotParam();

        AcpClient main = new AcpClient(".", "group-1", source);
        AcpClient team = new AcpClient(".", AcpClientIdentity.team(
                "team-acp-member-1", "team-acp-instance",
                "team/team-1/member-1", "owner-1", "team-1", "member-1",
                "Robot"), teamRobot);

        assertEquals(main.getClass(), team.getClass());
        assertEquals(main.getMcpConfigPaths(), team.getMcpConfigPaths());
        assertEquals(source.getAgentProvider(), team.getRobotParam().getAgentProvider());
        assertEquals(source.getModel(), team.getRobotParam().getModel());
        assertEquals(source.getApiKey(), team.getRobotParam().getApiKey());
        assertEquals(source.getHttpProxy(), team.getRobotParam().getHttpProxy());
        assertTrue(team.getClientIdentity().isTeam());
    }
}
