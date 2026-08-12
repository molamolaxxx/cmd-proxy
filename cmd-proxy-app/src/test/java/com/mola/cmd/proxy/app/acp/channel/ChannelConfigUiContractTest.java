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

        assertTrue(html.contains("<select onchange=\"config.channels['+i+'].defaultChatId=this.value"));
        assertTrue(html.contains("不设置默认目标"));
        assertTrue(html.contains("系统不会自动选择"));
        assertTrue(html.contains("最近发消息的排在前面"));
        assertTrue(html.contains("knownTargets.map"));
        assertFalse(html.contains("<input value=\"'+esc(ch.defaultChatId||'')+'\""));
    }
}
