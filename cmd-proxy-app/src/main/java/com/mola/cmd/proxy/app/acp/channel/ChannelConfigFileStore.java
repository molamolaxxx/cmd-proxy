package com.mola.cmd.proxy.app.acp.channel;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Safely persists channel runtime discoveries without exposing or replacing secrets. */
public final class ChannelConfigFileStore {
    private static final Object LOCK = new Object();

    private ChannelConfigFileStore() { }

    /**
     * Persists the most recently received conversation as the channel's proactive target.
     * For WeCom this value is a group {@code chatid} or a direct-message {@code userid}.
     */
    public static String setDefaultChatId(String channelId, String discoveredChatId)
            throws IOException {
        return setDefaultChatId(
                CmdProxyHome.resolve("acpConfig.json"), channelId, discoveredChatId);
    }

    /** Persists an inbound gate change without rewriting secrets from a masked UI model. */
    public static void setInboundEnabled(String channelId, boolean enabled) throws IOException {
        setInboundEnabled(CmdProxyHome.resolve("acpConfig.json"), channelId, enabled);
    }

    static void setInboundEnabled(Path configPath, String channelId, boolean enabled)
            throws IOException {
        String cleanChannelId = requireText(channelId, "channelId");
        synchronized (LOCK) {
            JSONObject root = JSON.parseObject(new String(
                    Files.readAllBytes(configPath), StandardCharsets.UTF_8));
            JSONObject matched = findChannel(root, cleanChannelId);
            matched.put("inboundEnabled", enabled);
            writeAtomically(configPath, JSON.toJSONString(root,
                    SerializerFeature.PrettyFormat, SerializerFeature.SortField));
        }
    }

    static String setDefaultChatId(Path configPath, String channelId,
                                   String discoveredChatId) throws IOException {
        String cleanChannelId = requireText(channelId, "channelId");
        String cleanChatId = requireText(discoveredChatId, "discoveredChatId");
        synchronized (LOCK) {
            JSONObject root = JSON.parseObject(new String(
                    Files.readAllBytes(configPath), StandardCharsets.UTF_8));
            JSONObject matched = findChannel(root, cleanChannelId);

            matched.put("defaultChatId", cleanChatId);
            writeAtomically(configPath, JSON.toJSONString(root,
                    SerializerFeature.PrettyFormat, SerializerFeature.SortField));
            return cleanChatId;
        }
    }

    private static JSONObject findChannel(JSONObject root, String channelId) throws IOException {
        JSONArray channels = root.getJSONArray("channels");
        if (channels == null) throw new IOException("channels configuration is missing");
        for (int i = 0; i < channels.size(); i++) {
            JSONObject channel = channels.getJSONObject(i);
            if (channel != null && channelId.equals(channel.getString("id"))) return channel;
        }
        throw new IOException("channel configuration not found: " + channelId);
    }

    private static void writeAtomically(Path configPath, String content) throws IOException {
        Path absolute = configPath.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException("configuration parent directory is missing");
        Path temp = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String requireText(String value, String field) {
        String clean = trim(value);
        if (clean.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return clean;
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
}
