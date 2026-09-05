package com.mola.cmd.proxy.app.acp.team;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TeamHistoryTalkToProjectionTest {

    @Test
    public void extractsTeamMessageWithoutExposingHarnessInstructions() {
        String prompt = "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                + "📨 [Fast Team 路由消息] 发送者 memberId: member-2-25993099\n"
                + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n"
                + "以下消息由当前 Fast Team 的严格队内路由投递，发送者身份已经过验证：\n\n"
                + "请检查刚才的构建结果。\n\n"
                + "─── 回复方式 ───\n"
                + "如需回复，请调用 talk_to MCP 工具。\n";

        TeamManager.TeamHistoryTalkTo projection =
                TeamManager.TeamHistoryTalkTo.parse(prompt);

        assertEquals("member-2-25993099", projection.senderTeamMemberId);
        assertEquals("请检查刚才的构建结果。", projection.content);
    }

    @Test
    public void leavesOrdinaryUserMessagesUntouched() {
        assertNull(TeamManager.TeamHistoryTalkTo.parse("普通用户消息"));
    }

    @Test
    public void projectsLegacyCmdProxyTalkToToolAsTeamEvent() {
        JsonObject input = new JsonObject();
        input.addProperty("target", "member-2");
        input.addProperty("content", "hello");
        JsonObject output = new JsonObject();
        output.addProperty("text", "[talkTo 结果] 消息已成功发送");

        TeamManager.TeamHistoryActionTool projection =
                TeamManager.TeamHistoryActionTool.parse(new ContextMessage(
                        "tool-1", "mcp__cmd-proxy-runtime__talk_to",
                        "completed", input, output));

        assertEquals("TALK_TO_SEND", projection.persistedEventType);
        assertEquals("member-2", projection.payload.get("targetTeamMemberId"));
        assertEquals("hello", projection.payload.get("content"));
        assertEquals("DELIVERED", projection.payload.get("delivery"));
    }

    @Test
    public void extractsExternalChannelMessageWithoutHarnessInstructions() {
        String prompt = "\n📨 [外部信道消息] 企业微信 / 小王\n"
                + "发送者昵称: 小王\n当前消息类型: text\n当前消息:\n"
                + "请确认报价\n\n第二段说明\n\n"
                + "本次消息已绑定其原始信道会话。";

        TeamManager.TeamHistoryChannelMessage projection =
                TeamManager.TeamHistoryChannelMessage.parse(prompt);

        assertEquals("channel:企业微信 / 小王", projection.label);
        assertEquals("请确认报价\n\n第二段说明", projection.content);
    }
}
