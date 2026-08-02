package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class AcpClientFeatureInitializerTest {

    @Test
    public void invokesEveryCapabilityHookInStableOrderForMainAndTeam() throws Exception {
        List<String> calls = new ArrayList<>();
        AcpClientFeatureInitializer initializer = new AcpClientFeatureInitializer(
                (context, client, robot) -> calls.add(context.getScope() + ":memory"),
                (context, client, robot) -> calls.add(context.getScope() + ":ability"),
                (context, client, robot) -> calls.add(context.getScope() + ":subAgent"),
                (context, client, robot) -> calls.add(context.getScope() + ":schedule"),
                (context, client, robot) -> calls.add(context.getScope() + ":talkTo"));
        AcpRobotParam robot = robot();
        AcpClient client = new AcpClient(".", "group-1", robot);

        initializer.initialize(AcpClientFeatureInitializer.Context.main("group-1"),
                client, robot);
        initializer.initialize(AcpClientFeatureInitializer.Context.team(
                "team-acp-member-1", "group-1", "team-1", "member-1"),
                client, robot);

        assertEquals(Arrays.asList(
                "MAIN:memory", "MAIN:ability", "MAIN:subAgent",
                "MAIN:schedule", "MAIN:talkTo",
                "TEAM:memory", "TEAM:ability", "TEAM:subAgent",
                "TEAM:schedule", "TEAM:talkTo"), calls);
    }

    @Test
    public void exposesDistinctTeamOwnerAndSourceKeys() {
        AcpClientFeatureInitializer.Context context =
                AcpClientFeatureInitializer.Context.team(
                        "team-acp-member-1", "group-source",
                        "team-1", "member-1");

        assertEquals("team-acp-member-1", context.getFeatureOwnerKey());
        assertEquals("group-source", context.getSourceGroupId());
        assertEquals("team-1", context.getTeamId());
        assertEquals("member-1", context.getTeamMemberId());
    }

    private static AcpRobotParam robot() {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("Robot");
        return robot;
    }
}
