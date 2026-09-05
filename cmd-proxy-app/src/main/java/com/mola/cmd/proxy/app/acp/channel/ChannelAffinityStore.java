package com.mola.cmd.proxy.app.acp.channel;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists only exclusive channel-to-Team-member claims. Once every member has a claim,
 * additional conversations use deterministic rendezvous hashing and need no stored row.
 */
public final class ChannelAffinityStore {
    private static final String SCHEMA_VERSION = "1";
    private static final ConcurrentHashMap<Path, Object> FILE_LOCKS = new ConcurrentHashMap<>();

    private final Path path;
    private final Object fileLock;

    public ChannelAffinityStore() {
        this(CmdProxyHome.resolve("channel-affinity.json"));
    }

    ChannelAffinityStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
        this.fileLock = FILE_LOCKS.computeIfAbsent(this.path, ignored -> new Object());
    }

    /**
     * Returns an existing exclusive claim, claims the highest-ranked free member, or falls
     * back to the highest-ranked member when every selectable member is already occupied.
     */
    public Selection select(String instanceId, String channelId, String teamId,
                            String routingKey, List<String> stableMemberIds,
                            List<String> selectableMemberIds) throws IOException {
        String scope = digest(requireText(instanceId, "instanceId") + "\n"
                + requireText(channelId, "channelId") + "\n"
                + requireText(teamId, "teamId"));
        String routeDigest = digest(requireText(routingKey, "routingKey"));
        List<String> stable = distinct(stableMemberIds);
        List<String> ranked = rendezvousOrder(routingKey, selectableMemberIds);
        if (ranked.isEmpty()) return null;

        synchronized (fileLock) {
            JSONObject root = load();
            JSONObject scopes = root.getJSONObject("scopes");
            if (scopes == null) {
                scopes = new JSONObject(true);
                root.put("scopes", scopes);
            }
            JSONObject claims = scopes.getJSONObject(scope);
            if (claims == null) {
                claims = new JSONObject(true);
                scopes.put(scope, claims);
            }

            boolean changed = pruneMissingMembers(claims, new HashSet<>(stable));
            String existing = claims.getString(routeDigest);
            if (existing != null && stable.contains(existing)) {
                if (changed) persist(root);
                return new Selection(existing, SelectionReason.EXISTING_CLAIM, routeDigest);
            }

            Set<String> occupied = new HashSet<>();
            for (Object value : claims.values()) {
                if (value != null) occupied.add(String.valueOf(value));
            }
            for (String memberId : ranked) {
                if (!occupied.contains(memberId)) {
                    claims.put(routeDigest, memberId);
                    persist(root);
                    return new Selection(memberId, SelectionReason.FREE_SLOT, routeDigest);
                }
            }
            if (changed) persist(root);
            return new Selection(ranked.get(0), SelectionReason.HASH_FALLBACK, routeDigest);
        }
    }

    /** Removes only one exact channel/Team affinity scope. */
    public void clearScope(String instanceId, String channelId, String teamId) throws IOException {
        String scope = digest(requireText(instanceId, "instanceId") + "\n"
                + requireText(channelId, "channelId") + "\n"
                + requireText(teamId, "teamId"));
        synchronized (fileLock) {
            if (!Files.exists(path)) return;
            JSONObject root = load();
            JSONObject scopes = root.getJSONObject("scopes");
            if (scopes != null && scopes.remove(scope) != null) persist(root);
        }
    }

    private JSONObject load() throws IOException {
        if (!Files.exists(path)) {
            JSONObject root = new JSONObject(true);
            root.put("schemaVersion", SCHEMA_VERSION);
            root.put("scopes", new JSONObject(true));
            return root;
        }
        try {
            JSONObject root = JSON.parseObject(new String(
                    Files.readAllBytes(path), StandardCharsets.UTF_8));
            if (root == null || !SCHEMA_VERSION.equals(root.getString("schemaVersion"))) {
                throw new IOException("unsupported channel affinity schema");
            }
            return root;
        } catch (RuntimeException e) {
            throw new IOException("channel affinity state is invalid", e);
        }
    }

    private void persist(JSONObject root) throws IOException {
        Path parent = path.getParent();
        if (parent == null) throw new IOException("channel affinity parent is missing");
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, JSON.toJSONString(root, SerializerFeature.PrettyFormat,
                    SerializerFeature.SortField).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static boolean pruneMissingMembers(JSONObject claims, Set<String> stable) {
        boolean[] changed = {false};
        claims.entrySet().removeIf(entry -> {
            boolean remove = entry.getValue() == null
                    || !stable.contains(String.valueOf(entry.getValue()));
            if (remove) changed[0] = true;
            return remove;
        });
        return changed[0];
    }

    static List<String> rendezvousOrder(String routingKey, List<String> memberIds) {
        List<String> result = distinct(memberIds);
        result.sort((left, right) -> {
            int compared = compareUnsigned(score(routingKey, right), score(routingKey, left));
            return compared != 0 ? compared : left.compareTo(right);
        });
        return result;
    }

    private static byte[] score(String routingKey, String memberId) {
        return sha256((routingKey + "\n" + memberId).getBytes(StandardCharsets.UTF_8));
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            int compared = Integer.compare(left[i] & 0xff, right[i] & 0xff);
            if (compared != 0) return compared;
        }
        return Integer.compare(left.length, right.length);
    }

    private static List<String> distinct(List<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) unique.add(value.trim());
        }
        return new ArrayList<>(unique);
    }

    private static String digest(String value) {
        byte[] bytes = sha256(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String requireText(String value, String field) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return clean;
    }

    public enum SelectionReason { EXISTING_CLAIM, FREE_SLOT, HASH_FALLBACK }

    public static final class Selection {
        private final String teamMemberId;
        private final SelectionReason reason;
        private final String routingDigest;

        Selection(String teamMemberId, SelectionReason reason, String routingDigest) {
            this.teamMemberId = teamMemberId;
            this.reason = reason;
            this.routingDigest = routingDigest;
        }

        public String getTeamMemberId() { return teamMemberId; }
        public SelectionReason getReason() { return reason; }
        public String getRoutingDigestPrefix() {
            return routingDigest.substring(0, Math.min(8, routingDigest.length()));
        }
    }
}
