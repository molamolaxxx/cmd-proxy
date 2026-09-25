package com.mola.cmd.proxy.app.acp.channel;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelConfigUiContractTest {
    @Test
    public void outboundTargetsAreAnEditableDiscoveryBackedList() throws Exception {
        InputStream input = getClass().getResourceAsStream("/configui/index.html");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        String html = new String(output.toByteArray(), StandardCharsets.UTF_8);

        assertTrue(html.contains("id=\"channelDialog\""));
        assertTrue(html.contains("onclick=\"openChannelDialog(-1)\""));
        assertTrue(html.contains("onclick=\"openChannelDialog('+i+')\""));
        assertTrue(html.contains("async function saveChannelDialog()"));
        assertTrue(html.contains("openChannelDialog('+i+',\\'copy\\')"));
        assertFalse(html.contains("await applyChannelConfig(insertIndex)"));
        assertTrue(html.contains("消息渠道已复制并保存"));
        assertTrue(html.contains("applyChannelConfig(insertIndex).then(function()"));
        assertTrue(html.contains("async function deleteChannel(i)"));
        assertTrue(html.contains("消息渠道已删除并保存"));
        assertTrue(html.contains("if(!ok){config.channels.splice(i,0,channel)"));
        assertTrue(html.contains("var ok=await saveConfig(true)"));
        assertTrue(html.contains("function normalizeChannelOutboundTargets("));
        assertTrue(html.contains("function isValidChannelOutboundTargetId("));
        assertTrue(html.contains("目标 ID 只能包含中文、字母、数字、下划线和短横线"));
        assertTrue(html.contains("outboundTargets:[defaultChannelOutboundTarget(id)]"));
        assertTrue(html.contains("function setChannelDraftId("));
        assertTrue(html.contains("oninput=\"setChannelDraftId(this.value)\""));
        assertTrue(html.contains("function renderChannelOutboundTargetList("));
        assertTrue(html.contains("function refreshChannelKnownTargets("));
        assertTrue(html.contains("setInterval(refreshChannelKnownTargets,2000)"));
        assertTrue(html.contains("function stopChannelKnownTargetsRefresh("));
        assertTrue(html.contains("最新来信排在最前"));
        assertTrue(html.contains("添加推送目标"));
        assertTrue(html.contains("Agent 选择提示"));
        assertTrue(html.contains("暂不选择企微会话"));
        assertTrue(html.contains("function addChannelOutboundTarget("));
        assertTrue(html.contains("function removeChannelOutboundTarget("));
        assertFalse(html.contains("channelDialogDraft.defaultChatId=this.value"));
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
