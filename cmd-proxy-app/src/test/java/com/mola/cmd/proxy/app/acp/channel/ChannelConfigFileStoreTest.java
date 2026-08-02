package com.mola.cmd.proxy.app.acp.channel;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ChannelConfigFileStoreTest {
    @Test
    public void latestConversationIsPersistedWithoutChangingOtherFields() throws Exception {
        Path file = Files.createTempFile("channel-config", ".json");
        try {
            Files.write(file, ("{\"other\":\"kept\",\"channels\":[{"
                    + "\"id\":\"wecom-1\",\"secret\":\"sensitive\",\"defaultChatId\":\"\"}]}"
            ).getBytes(StandardCharsets.UTF_8));

            assertEquals("chat-first", ChannelConfigFileStore.setDefaultChatId(
                    file, "wecom-1", "chat-first"));
            assertEquals("user-second", ChannelConfigFileStore.setDefaultChatId(
                    file, "wecom-1", "user-second"));

            JSONObject saved = JSON.parseObject(new String(
                    Files.readAllBytes(file), StandardCharsets.UTF_8));
            JSONObject channel = saved.getJSONArray("channels").getJSONObject(0);
            assertEquals("user-second", channel.getString("defaultChatId"));
            assertEquals("sensitive", channel.getString("secret"));
            assertEquals("kept", saved.getString("other"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void inboundSwitchIsPersistedWithoutChangingOtherChannelFields() throws Exception {
        Path file = Files.createTempFile("channel-config", ".json");
        try {
            Files.write(file, ("{\"other\":\"kept\",\"channels\":[{"
                    + "\"id\":\"wecom-1\",\"secret\":\"sensitive\"}]}"
            ).getBytes(StandardCharsets.UTF_8));

            ChannelConfigFileStore.setInboundEnabled(file, "wecom-1", false);

            JSONObject saved = JSON.parseObject(new String(
                    Files.readAllBytes(file), StandardCharsets.UTF_8));
            JSONObject channel = saved.getJSONArray("channels").getJSONObject(0);
            assertFalse(channel.getBooleanValue("inboundEnabled"));
            assertEquals("sensitive", channel.getString("secret"));
            assertEquals("kept", saved.getString("other"));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
