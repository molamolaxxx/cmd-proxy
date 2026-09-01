package com.mola.cmd.proxy.app.acp.starweave;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;

/** Builds stable, environment-scoped identities for Starweave MAIN clients. */
public final class StarweaveIdentity {

    public static final String OWNER_PREFIX = "starweave-";
    private static final String INSTANCE_PATTERN = "[a-zA-Z0-9._-]+";
    private static final String SAFE_ID_PATTERN = "[a-zA-Z0-9._-]+";
    private static final String HASHED_ACP_PREFIX = "acp-key-";

    private StarweaveIdentity() {
    }

    public static String ownerId(String instanceId) {
        String safeInstanceId = requireInstanceId(instanceId);
        return OWNER_PREFIX + safeInstanceId;
    }

    public static boolean isReservedOwner(String value) {
        return value != null && value.trim().startsWith(OWNER_PREFIX);
    }

    /** Prevents a MolaChat principal from occupying the Starweave owner namespace. */
    public static void validateMolaChatChatterIds(Collection<String> chatterIds) {
        if (chatterIds == null) return;
        for (String chatterId : chatterIds) {
            if (isReservedOwner(chatterId)) {
                throw new IllegalArgumentException(
                        "MolaChat chatterId uses reserved Starweave namespace: "
                                + chatterId);
            }
        }
    }

    public static String acpId(String robotName) {
        String name = requireText(robotName, "robotName");
        return "acp-" + name.replace(" ", "_").replace("\u3000", "_");
    }

    public static String groupId(String instanceId, String robotName) {
        String[] parts = {ownerId(instanceId), logicalRobotId(robotName)};
        Arrays.sort(parts);
        return parts[0] + parts[1];
    }

    /**
     * Keeps legacy IDs for already-safe robot names and derives a stable,
     * persistence-safe key for display names containing Unicode or path symbols.
     */
    static String logicalRobotId(String robotName) {
        String candidate = acpId(robotName);
        if (candidate.matches(SAFE_ID_PATTERN)
                && !candidate.startsWith(HASHED_ACP_PREFIX)) {
            return candidate;
        }
        return HASHED_ACP_PREFIX + safeRobotKey(robotName).substring("robot-".length());
    }

    public static String transportGroup(String instanceId, String robotName) {
        return "starweave-local:" + requireInstanceId(instanceId)
                + ":" + safeRobotKey(robotName);
    }

    public static String historyNamespace(String robotName) {
        return "starweave/" + safeRobotKey(robotName);
    }

    public static AcpClientIdentity identity(String instanceId, String robotName) {
        return AcpClientIdentity.starweave(
                groupId(instanceId, robotName),
                transportGroup(instanceId, robotName),
                historyNamespace(robotName),
                ownerId(instanceId),
                requireText(robotName, "robotName"));
    }

    static String safeRobotKey(String robotName) {
        String name = requireText(robotName, "robotName");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(name.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("robot-");
            for (int i = 0; i < 12; i++) {
                result.append(String.format("%02x", bytes[i] & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String requireInstanceId(String value) {
        String instanceId = requireText(value, "instanceId");
        if (!instanceId.matches(INSTANCE_PATTERN)
                || ".".equals(instanceId) || "..".equals(instanceId)) {
            throw new IllegalArgumentException("instanceId is not transport-safe");
        }
        return instanceId;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
