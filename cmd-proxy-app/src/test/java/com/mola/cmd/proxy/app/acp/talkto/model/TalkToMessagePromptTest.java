package com.mola.cmd.proxy.app.acp.talkto.model;

import org.junit.Test;
import com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TalkToMessagePromptTest {

    @Test
    public void replyInstructionUsesMcpToolProtocol() {
        String prompt = new TalkToMessage("Code Chat Dev", "test", 0).buildPrompt();

        assertTrue(prompt.contains("talk_to MCP 工具"));
        assertTrue(prompt.contains("Code Chat Dev"));
        assertFalse(prompt.contains("收到消息不代表必须回复"));
        assertFalse(prompt.contains("最终结果默认结束通信链"));
        assertFalse(prompt.contains("工具调用结果会作为本轮工具返回值"));
        assertFalse(prompt.contains("\"action\""));
        assertFalse(prompt.contains("输出 JSON 后立即结束回复"));
        assertFalse(prompt.contains("_depth"));
    }

    @Test
    public void carriesChannelNeutralPrincipalWithoutAddingItToPrompt() {
        AuthPrincipalContext principal = new AuthPrincipalContext(
                "user-a", "Alice", "WECOM", "wecom-main");
        TalkToMessage message = new TalkToMessage(
                "sender", "content", 1, Collections.emptyList(), principal);

        assertTrue(message.getAuthPrincipalContext() == principal);
        assertFalse(message.buildPrompt().contains("user-a"));
    }
}
