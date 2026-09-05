package com.mola.cmd.proxy.app.acp.channel;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelConfigUiContractTest {
    @Test
    public void defaultTargetIsAUserSelectedDiscoveryDropdown() throws Exception {
        InputStream input = getClass().getResourceAsStream("/configui/index.html");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        String html = new String(output.toByteArray(), StandardCharsets.UTF_8);

        assertTrue(html.contains("id=\"channelDialog\""));
        assertTrue(html.contains("onclick=\"openChannelDialog(-1)\""));
        assertTrue(html.contains("onclick=\"openChannelDialog('+i+')\""));
        assertTrue(html.contains("function saveChannelDialog()"));
        assertTrue(html.contains("<select onchange=\"channelDialogDraft.defaultChatId=this.value"));
        assertTrue(html.contains("不设置默认目标"));
        assertTrue(html.contains("系统不会自动选择"));
        assertTrue(html.contains("targets.map"));
        assertFalse(html.contains("<input value=\"'+esc(ch.defaultChatId||'')+'\""));
        assertTrue(html.contains("<label>用户行为</label><select onchange=\"channelDialogDraft.userBehavior=this.value"));
        assertTrue(html.contains("<option value=\"QUEUE\""));
        assertTrue(html.contains("<option value=\"INTERRUPT\""));
        assertTrue(html.contains("userBehavior:'QUEUE'"));
        assertTrue(html.contains("value=\"RANDOM\""));
        assertTrue(html.contains("value=\"AFFINITY\""));
        assertTrue(html.contains("单聊按 userId、群聊按 chatId"));
        assertTrue(html.contains("function setChannelDraftMemberSelection("));
        assertTrue(html.contains("b.teamMemberSelection||'FIXED'"));
        assertTrue(html.contains("card channel-card agent-card"));
        assertTrue(html.contains("class=\"agent-summary-label\">消息处理"));
        assertTrue(html.contains("class=\"agent-summary-label\">路由策略"));
        assertTrue(html.contains("class=\"agent-summary-label\">消息接收"));
        assertFalse(html.contains("oninput=\"config.channels['+i+'].secret=this.value"));
    }
}
