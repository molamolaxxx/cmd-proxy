package com.mola.cmd.proxy.app.acp.talkto;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.talkto.model.ContactRef;
import com.mola.cmd.proxy.app.acp.talkto.model.ExternalTalkToContact;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TalkToContextInjectorTest {

    @Test
    public void keepsDynamicCallingGuidanceWithoutRepeatingToolContract() {
        String context = new TalkToContextInjector().buildContext(
                Collections.emptyList(), Collections.emptyMap(), "self");

        assertTrue(context.contains("<agent-team>"));
        assertTrue(context.contains("<external-channel>"));
        assertFalse(context.contains("<external-channels>"));
        assertFalse(context.contains("<external-channel-routing>"));
        assertTrue(context.contains("talk_to MCP 工具"));
        assertTrue(context.contains("回复当前来信时，将 target 设为“回复”"));
        assertTrue(context.contains("{\"target\":\"回复\",\"content\":\"处理结果\"}"));
        assertTrue(context.contains("当前未配置主动通知目标"));
        assertFalse(context.contains("只有用户明确要求通知"));
        assertFalse(context.contains("主动通知完成后"));
        assertTrue(context.contains("重要运行时约束"));
        assertTrue(context.contains("禁止发送“收到”"));
        assertFalse(context.contains("目标 Agent 忙碌时消息会排队"));
        assertFalse(context.contains("不表示接收方已处理"));
        assertFalse(context.contains("与 dispatch_subagent 的区别"));
    }

    @Test
    public void separatesAgentsFromExternalChannelTargetsAndAddsRoutingRules() {
        ContactRef agent = new ContactRef("reviewer", "代码审查");
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("reviewer");
        TalkToContextInjector injector = new TalkToContextInjector(
                group -> Collections.singletonList(new ExternalTalkToContact(
                        "channel:release-group", "release-group", "仅用于发布通知")),
                "group-1");

        String context = injector.buildContext(Collections.singletonList(agent),
                Collections.singletonMap("reviewer", robot), "self");

        assertTrue(context.contains("可联系的 Agent"));
        assertTrue(context.contains("reviewer: 代码审查"));
        assertTrue(context.indexOf("</agent-team>")
                < context.indexOf("<external-channel>"));
        assertTrue(context.contains("release-group（target: channel:release-group）"));
        assertTrue(context.contains("{\"target\":\"channel:release-group\",\"content\":\"通知内容\"}"));
        assertTrue(context.contains("只有用户明确要求通知"));
        assertFalse(context.contains("主动通知完成后"));
        assertFalse(context.contains("channel:*"));
    }
}
