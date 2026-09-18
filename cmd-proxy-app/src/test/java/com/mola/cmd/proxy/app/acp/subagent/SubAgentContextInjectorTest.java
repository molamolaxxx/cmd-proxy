package com.mola.cmd.proxy.app.acp.subagent;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.subagent.model.SubAgentRef;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SubAgentContextInjectorTest {

    @Test
    public void keepsDynamicRosterAndCallingGuidanceWithoutRepeatingFieldContract() {
        AcpRobotParam worker = new AcpRobotParam(
                "Worker", "处理独立任务", "/workspace/worker", "");
        String context = new SubAgentContextInjector().buildContext(
                Collections.singletonList(new SubAgentRef("Worker", "专业能力")),
                Collections.singletonMap("Worker", worker), "Main");

        assertTrue(context.contains("<available-sub-agents>"));
        assertTrue(context.contains("dispatch_subagent MCP 工具"));
        assertTrue(context.contains("名称（name）: `Worker`"));
        assertTrue(context.contains("工作目录: /workspace/worker"));
        assertTrue(context.contains("合法的 agent 名称"));
        assertFalse(context.contains("title 是任务的简短名称"));
        assertFalse(context.contains("tasks 数组中可以包含多个任务"));
        assertFalse(context.contains("工具返回聚合结果"));
    }
}
