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
    private static final int MAX_KNOWN_CHAT_TARGETS = 200;

    private ChannelConfigFileStore() { }

    /** Persists an inbound gate change without rewriting secrets from a masked UI model. */
    public static void setInboundEnabled(String channelId, boolean enabled) throws IOException {
        setInboundEnabled(CmdProxyHome.resolve("acpConfig.json"), channelId, enabled);
    }

    static void setInboundEnabled(Path configPath, String channelId, boolean enabled)
            throws IOException {
        setChannelBoolean(configPath, channelId, "inboundEnabled", enabled);
    }

    /** Persists the direct-message gate without restarting the channel connection. */
    public static void setPrivateChatEnabled(String channelId, boolean enabled) throws IOException {
        setPrivateChatEnabled(CmdProxyHome.resolve("acpConfig.json"), channelId, enabled);
    }

    static void setPrivateChatEnabled(Path configPath, String channelId, boolean enabled)
            throws IOException {
        setChannelBoolean(configPath, channelId, "privateChatEnabled", enabled);
    }

    /** Records a selectable proactive target without changing the user's defaultChatId. */
    public static boolean recordKnownChatTarget(String channelId, String targetId,
                                                String displayName, String chatType)
            throws IOException {
        return recordKnownChatTarget(CmdProxyHome.resolve("acpConfig.json"), channelId,
                targetId, displayName, chatType);
    }

    static boolean recordKnownChatTarget(Path configPath, String channelId, String targetId,
                                         String displayName, String chatType) throws IOException {
        String cleanChannelId = requireText(channelId, "channelId");
        String cleanTargetId = requireText(targetId, "targetId");
        String cleanDisplayName = truncate(requireText(displayName, "displayName"), 100);
        String cleanChatType = requireText(chatType, "chatType");
        synchronized (LOCK) {
            JSONObject root = JSON.parseObject(new String(
                    Files.readAllBytes(configPath), StandardCharsets.UTF_8));
            JSONObject matched = findChannel(root, cleanChannelId);
            JSONArray targets = matched.getJSONArray("knownChatTargets");
            if (targets == null) {
                targets = new JSONArray();
                matched.put("knownChatTargets", targets);
            }
            for (int i = 0; i < targets.size(); i++) {
                JSONObject target = targets.getJSONObject(i);
                if (target == null || !cleanTargetId.equals(target.getString("id"))
                        || !cleanChatType.equals(target.getString("chatType"))) continue;
                if (i == 0 && cleanDisplayName.equals(target.getString("displayName"))) {
                    return false;
                }
                target.put("displayName", cleanDisplayName);
                targets.remove(i);
                targets.add(0, target);
                writeAtomically(configPath, JSON.toJSONString(root,
                        SerializerFeature.PrettyFormat, SerializerFeature.SortField));
                return true;
            }
            JSONObject target = new JSONObject(true);
            target.put("id", cleanTargetId);
            target.put("displayName", cleanDisplayName);
            target.put("chatType", cleanChatType);
            targets.add(0, target);
            while (targets.size() > MAX_KNOWN_CHAT_TARGETS) {
                targets.remove(targets.size() - 1);
            }
            writeAtomically(configPath, JSON.toJSONString(root,
                    SerializerFeature.PrettyFormat, SerializerFeature.SortField));
            return true;
        }
    }

    /** Atomically saves UI configuration while retaining runtime-owned discoveries and secrets. */
    public static void saveUiConfig(JSONObject submitted, String secretMask) throws IOException {
        saveUiConfig(CmdProxyHome.resolve("acpConfig.json"), submitted, secretMask);
    }

    static void saveUiConfig(Path configPath, JSONObject submitted, String secretMask)
            throws IOException {
        synchronized (LOCK) {
            JSONObject previous = Files.exists(configPath)
                    ? JSON.parseObject(new String(Files.readAllBytes(configPath),
                            StandardCharsets.UTF_8))
                    : new JSONObject();
            JSONArray channels = submitted.getJSONArray("channels");
            JSONArray oldChannels = previous.getJSONArray("channels");
            if (channels != null) {
                for (int i = 0; i < channels.size(); i++) {
                    JSONObject channel = channels.getJSONObject(i);
                    if (channel == null) continue;
                    JSONObject old = findChannelOrNull(oldChannels, channel.getString("id"));
                    String secret = channel.getString("secret");
                    if (isBlank(secret) || secretMask.equals(secret)) {
                        String oldSecret = old == null ? null : old.getString("secret");
                        if (isBlank(oldSecret)) channel.remove("secret");
                        else channel.put("secret", oldSecret);
                    }
                    JSONArray targets = old == null ? null
                            : old.getJSONArray("knownChatTargets");
                    if (targets == null) channel.remove("knownChatTargets");
                    else channel.put("knownChatTargets", targets);
                }
            }
            writeAtomically(configPath, JSON.toJSONString(submitted,
                    SerializerFeature.PrettyFormat, SerializerFeature.SortField));
        }
    }

    private static void setChannelBoolean(Path configPath, String channelId,
                                          String field, boolean enabled) throws IOException {
        String cleanChannelId = requireText(channelId, "channelId");
        synchronized (LOCK) {
            JSONObject root = JSON.parseObject(new String(
                    Files.readAllBytes(configPath), StandardCharsets.UTF_8));
            JSONObject matched = findChannel(root, cleanChannelId);
            matched.put(field, enabled);
            writeAtomically(configPath, JSON.toJSONString(root,
                    SerializerFeature.PrettyFormat, SerializerFeature.SortField));
        }
    }

    private static JSONObject findChannel(JSONObject root, String channelId) throws IOException {
        JSONArray channels = root.getJSONArray("channels");
        if (channels == null) throw new IOException("channels configuration is missing");
        JSONObject matched = findChannelOrNull(channels, channelId);
        if (matched != null) return matched;
        throw new IOException("channel configuration not found: " + channelId);
    }

    private static JSONObject findChannelOrNull(JSONArray channels, String channelId) {
        if (channels == null || isBlank(channelId)) return null;
        for (int i = 0; i < channels.size(); i++) {
            JSONObject channel = channels.getJSONObject(i);
            if (channel != null && channelId.equals(channel.getString("id"))) return channel;
        }
        return null;
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
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return clean;
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
