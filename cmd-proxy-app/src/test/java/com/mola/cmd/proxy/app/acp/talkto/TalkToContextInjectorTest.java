package com.mola.cmd.proxy.app.acp.talkto;

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
        assertTrue(context.contains("talk_to MCP 工具"));
        assertTrue(context.contains("重要运行时约束"));
        assertTrue(context.contains("禁止发送“收到”"));
        assertFalse(context.contains("目标 Agent 忙碌时消息会排队"));
        assertFalse(context.contains("不表示接收方已处理"));
        assertFalse(context.contains("与 dispatch_subagent 的区别"));
    }
}
