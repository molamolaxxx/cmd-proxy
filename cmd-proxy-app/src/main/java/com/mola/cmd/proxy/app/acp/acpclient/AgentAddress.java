package com.mola.cmd.proxy.app.acp.acpclient;

import com.alibaba.fastjson.JSONObject;

import java.util.Objects;

/** Stable structured address used across channel, TalkTo and future remote Team routing. */
public final class AgentAddress {
    private final String instanceId;
    private final ClientSurface surface;
    private final String ownerId;
    private final String robotId;

    public AgentAddress(String instanceId, ClientSurface surface,
                        String ownerId, String robotId) {
        this.instanceId = require(instanceId, "instanceId");
        this.surface = Objects.requireNonNull(surface, "surface");
        this.ownerId = require(ownerId, "ownerId");
        this.robotId = require(robotId, "robotId");
    }

    public String getInstanceId() { return instanceId; }
    public ClientSurface getSurface() { return surface; }
    public String getOwnerId() { return ownerId; }
    public String getRobotId() { return robotId; }

    public String canonical() {
        return instanceId + "/" + surface.name() + "/" + ownerId + "/" + robotId;
    }

    public JSONObject toJson() {
        JSONObject value = new JSONObject(true);
        value.put("instanceId", instanceId);
        value.put("surface", surface.name());
        value.put("ownerId", ownerId);
        value.put("robotId", robotId);
        value.put("canonical", canonical());
        return value;
    }

    private static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
