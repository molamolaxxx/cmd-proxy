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
    public void omitsExternalChannelContextWhenNoChannelIsConfigured() {
        String context = new TalkToContextInjector().buildContext(
                Collections.emptyList(), Collections.emptyMap(), "self");

        assertTrue(context.contains("<agent-team>"));
        assertFalse(context.contains("<external-channel>"));
        assertFalse(context.contains("<external-channels>"));
        assertFalse(context.contains("<external-channel-routing>"));
        assertTrue(context.contains("talk_to MCP 工具"));
        assertFalse(context.contains("只有用户明确要求通知"));
        assertFalse(context.contains("主动通知完成后"));
        assertTrue(context.contains("重要运行时约束"));
        assertTrue(context.contains("禁止向其他 Agent 发送“收到”"));
        assertTrue(context.contains("除非发送方明确要求确认收到或作答"));
        assertTrue(context.contains("以下规则仅约束通过 talk_to 向其他 Agent 发送的消息"));
        assertTrue(context.contains("不限制企微等外部信道的回复次数和时机"));
        assertTrue(context.contains("用户要求先确认、处理中同步进度或分多次回复时，应遵循用户要求"));
        assertTrue(context.contains("“好的收到”等面向真实用户的确认回复允许发送"));
        assertTrue(context.contains("默认结束与该 Agent 的本次通信链"));
        assertFalse(context.contains("只有在产生最终结果"));
        assertFalse(context.contains("目标 Agent 忙碌时消息会排队"));
        assertFalse(context.contains("不表示接收方已处理"));
        assertFalse(context.contains("与 dispatch_subagent 的区别"));
    }

    @Test
    public void explainsReplyRoutingWhenChannelHasNoProactiveTargets() {
        ExternalTalkToContactProvider provider = new ExternalTalkToContactProvider() {
            @Override
            public java.util.List<ExternalTalkToContact> contactsForGroup(String groupId) {
                return Collections.emptyList();
            }

            @Override
            public boolean hasEnabledChannelForGroup(String groupId) {
                return "group-1".equals(groupId);
            }
        };
        String context = new TalkToContextInjector(provider, "group-1").buildContext(
                Collections.emptyList(), Collections.emptyMap(), "self");

        assertTrue(context.contains("<external-channel>"));
        assertTrue(context.contains("外部信道连接企业微信等外部消息平台"));
        assertTrue(context.contains("处理外部信道来信时，将 target 设为“回复”"));
        assertTrue(context.contains("{\"target\":\"回复\",\"content\":\"处理结果\"}"));
        assertTrue(context.contains("同一轮处理中，可按需多次调用“回复”"));
        assertTrue(context.contains("当前未配置主动通知目标"));
        assertFalse(context.contains("只有用户明确要求通知"));
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
        assertTrue(context.contains("外部信道连接企业微信等外部消息平台"));
        assertTrue(context.contains("同一轮处理中，可按需多次调用“回复”"));
        assertTrue(context.contains("release-group（target: channel:release-group）"));
        assertTrue(context.contains("{\"target\":\"channel:release-group\",\"content\":\"通知内容\"}"));
        assertTrue(context.contains("只有用户明确要求通知"));
        assertFalse(context.contains("主动通知完成后"));
        assertFalse(context.contains("channel:*"));
    }
}
