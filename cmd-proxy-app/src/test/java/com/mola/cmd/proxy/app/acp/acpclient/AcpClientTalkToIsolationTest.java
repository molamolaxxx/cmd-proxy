package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.subagent.SubAgentContextInjector;
import com.mola.cmd.proxy.app.acp.talkto.TalkToContextInjector;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertSame;

public class AcpClientTalkToIsolationTest {

    @Test
    public void teamTalkToWhitelistDoesNotOverwriteSubAgentRegistry() throws Exception {
        AcpRobotParam source = new AcpRobotParam();
        source.setName("source");
        AcpClient client = new AcpClient(".", AcpClientIdentity.team(
                "team-acp-member-1", "team-acp-instance",
                "team/team-1/member-1", "owner-1",
                "team-1", "member-1", "source"), source);
        Map<String, AcpRobotParam> subAgentRegistry = new HashMap<>();
        subAgentRegistry.put("worker", new AcpRobotParam());
        Map<String, AcpRobotParam> emptyTeamTalkToRegistry = Collections.emptyMap();

        client.setSubAgentSupport(null, new SubAgentContextInjector(), subAgentRegistry);
        client.setTalkToSupport(
                new TalkToDispatcher(Collections.emptyMap(),
                        AcpClientRegistry.getInstance(), Collections.emptyMap()),
                new TalkToContextInjector(), emptyTeamTalkToRegistry);

        assertSame(subAgentRegistry, field(client, "globalRobotRegistry"));
        assertSame(emptyTeamTalkToRegistry, field(client, "talkToRobotRegistry"));
    }

    private static Object field(AcpClient client, String name) throws Exception {
        Field field = AcpClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(client);
    }
}
