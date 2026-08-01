package com.mola.cmd.proxy.app.acp.team;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class TeamSourceRobotSnapshots {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private TeamSourceRobotSnapshots() {
    }

    static TeamSourceRobotSnapshot capture(String sourceGroupId, String sourceRobotId,
                                           AcpRobotParam robot) {
        return new TeamSourceRobotSnapshot(sourceGroupId, sourceRobotId,
                fingerprint(robot), System.currentTimeMillis(), robot);
    }

    static String fingerprint(AcpRobotParam robot) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(GSON.toJson(robot).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
