package com.mola.cmd.proxy.app.acp.channel;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelConfigFileStoreTest {
    @Test
    public void inboundConversationsBecomeOptionsWithoutSelectingDefault() throws Exception {
        Path file = Files.createTempFile("channel-config", ".json");
        try {
            Files.write(file, ("{\"channels\":[{\"id\":\"wecom-1\","
                    + "\"defaultChatId\":\"\",\"secret\":\"sensitive\"}]}"
            ).getBytes(StandardCharsets.UTF_8));

            assertTrue(ChannelConfigFileStore.recordKnownChatTarget(
                    file, "wecom-1", "group-1", "研发群", "group"));
            assertTrue(ChannelConfigFileStore.recordKnownChatTarget(
                    file, "wecom-1", "user-1", "张三", "single"));
            assertTrue(ChannelConfigFileStore.recordKnownChatTarget(
                    file, "wecom-1", "group-1", "研发群", "group"));
            assertTrue(ChannelConfigFileStore.recordKnownChatTarget(
                    file, "wecom-1", "user-1", "张三新昵称", "single"));
            assertFalse(ChannelConfigFileStore.recordKnownChatTarget(
                    file, "wecom-1", "user-1", "张三新昵称", "single"));

            JSONObject channel = read(file).getJSONArray("channels").getJSONObject(0);
            assertEquals("", channel.getString("defaultChatId"));
            assertEquals(2, channel.getJSONArray("knownChatTargets").size());
            assertEquals("张三新昵称", channel.getJSONArray("knownChatTargets")
                    .getJSONObject(0).getString("displayName"));
            assertEquals("研发群", channel.getJSONArray("knownChatTargets")
                    .getJSONObject(1).getString("displayName"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void uiSaveKeepsSystemDiscoveredOptionsAndSelectedDefault() throws Exception {
        Path file = Files.createTempFile("channel-config", ".json");
        try {
            Files.write(file, ("{\"channels\":[{\"id\":\"wecom-1\","
                    + "\"defaultChatId\":\"\",\"secret\":\"sensitive\","
                    + "\"knownChatTargets\":[{\"id\":\"group-1\","
                    + "\"displayName\":\"研发群\",\"chatType\":\"group\"}]}]}"
            ).getBytes(StandardCharsets.UTF_8));
            JSONObject submitted = JSON.parseObject("{\"channels\":[{"
                    + "\"id\":\"wecom-1\",\"defaultChatId\":\"group-1\","
                    + "\"secret\":\"********\",\"knownChatTargets\":[{"
                    + "\"id\":\"fake\",\"displayName\":\"fake\","
                    + "\"chatType\":\"single\"}]}]}");

            ChannelConfigFileStore.saveUiConfig(file, submitted, "********");

            JSONObject channel = read(file).getJSONArray("channels").getJSONObject(0);
            assertEquals("group-1", channel.getString("defaultChatId"));
            assertEquals("sensitive", channel.getString("secret"));
            assertEquals(1, channel.getJSONArray("knownChatTargets").size());
            assertEquals("group-1", channel.getJSONArray("knownChatTargets")
                    .getJSONObject(0).getString("id"));
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

    @Test
    public void privateChatSwitchIsPersistedWithoutChangingOtherChannelFields() throws Exception {
        Path file = Files.createTempFile("channel-config", ".json");
        try {
            Files.write(file, ("{\"other\":\"kept\",\"channels\":[{"
                    + "\"id\":\"wecom-1\",\"secret\":\"sensitive\"}]}"
            ).getBytes(StandardCharsets.UTF_8));

            ChannelConfigFileStore.setPrivateChatEnabled(file, "wecom-1", false);

            JSONObject saved = JSON.parseObject(new String(
                    Files.readAllBytes(file), StandardCharsets.UTF_8));
            JSONObject channel = saved.getJSONArray("channels").getJSONObject(0);
            assertFalse(channel.getBooleanValue("privateChatEnabled"));
            assertEquals("sensitive", channel.getString("secret"));
            assertEquals("kept", saved.getString("other"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static JSONObject read(Path file) throws Exception {
        return JSON.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }
}
