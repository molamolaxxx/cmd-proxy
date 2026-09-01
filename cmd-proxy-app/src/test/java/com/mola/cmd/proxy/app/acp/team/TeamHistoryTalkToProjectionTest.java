package com.mola.cmd.proxy.app.acp.team;

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
}
