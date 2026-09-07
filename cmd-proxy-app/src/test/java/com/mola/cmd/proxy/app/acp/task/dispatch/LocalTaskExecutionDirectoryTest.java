package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.starweave.StarweaveIdentity;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class LocalTaskExecutionDirectoryTest {
    @Test
    public void resolvesConfiguredStarweaveTargetBeforeAnySessionHasOpened() {
        String instance = "instance-local";
        String owner = StarweaveIdentity.ownerId(instance);
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("Open Code Skill");
        String group = StarweaveIdentity.identity(instance, robot.getName()).getLogicalId();
        JSONObject target = new JSONObject();
        target.put("instanceId", instance);
        target.put("ownerId", owner);
        target.put("agentId", "acp-Open_Code_Skill");
        target.put("groupId", group);
        LocalTaskExecutionDirectory directory = new LocalTaskExecutionDirectory(
                instance, owner, Collections.singletonMap(group, robot), null, null);
        assertEquals(group, directory.resolveAgent(target).getString("groupId"));
    }

    @Test
    public void rebuildsAgentAddressFromAuthoritativeGroupConfiguration() {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("Agent One");
        robot.setAgentProvider("CODEX_ACP");
        LocalTaskExecutionDirectory directory = new LocalTaskExecutionDirectory(
                "instance-1", "owner-1", Collections.singletonMap("group-1", robot),
                null, null);
        JSONObject target = new JSONObject(true);
        target.put("type", "AGENT");
        target.put("instanceId", "instance-1");
        target.put("ownerId", "owner-1");
        target.put("agentId", "acp-Agent_One");

        JSONObject assignee = directory.resolveAgent(target);

        assertEquals("group-1", assignee.getString("groupId"));
        assertEquals("acp-Agent_One", assignee.getString("robotId"));
        assertEquals("Agent One", assignee.getString("robotNameSnapshot"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsSelfReportedForeignInstance() {
        LocalTaskExecutionDirectory directory = new LocalTaskExecutionDirectory(
                "instance-1", "owner-1", Collections.emptyMap(), null, null);
        JSONObject target = new JSONObject(true);
        target.put("instanceId", "foreign");
        directory.resolveAgent(target);
    }

    @Test
    public void normalizesFullWidthSpacesLikeRuntimeSourceIds() {
        assertEquals("acp-Agent_One",
                LocalTaskExecutionDirectory.robotId("Agent\u3000One"));
    }
}
