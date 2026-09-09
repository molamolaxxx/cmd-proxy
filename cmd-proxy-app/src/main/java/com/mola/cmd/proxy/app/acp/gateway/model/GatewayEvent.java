package com.mola.cmd.proxy.app.acp.gateway.model;

import com.alibaba.fastjson.JSONObject;

/** Immutable, versioned event delivered to external gateway clients. */
public final class GatewayEvent {
    private final String gatewayId;
    private final String eventId;
    private final long eventSeq;
    private final String sessionId;
    private final long epoch;
    private final String turnId;
    private final String cardId;
    private final long timestamp;
    private final String type;
    private final JSONObject payload;
    private final JSONObject source;

    public GatewayEvent(String gatewayId, String eventId, long eventSeq,
                        String sessionId, long epoch, String turnId, String cardId,
                        long timestamp, String type, JSONObject payload, JSONObject source) {
        this.gatewayId = gatewayId;
        this.eventId = eventId;
        this.eventSeq = eventSeq;
        this.sessionId = sessionId;
        this.epoch = epoch;
        this.turnId = turnId;
        this.cardId = cardId;
        this.timestamp = timestamp;
        this.type = type;
        this.payload = payload == null ? new JSONObject(true) : new JSONObject(payload);
        this.source = source == null ? new JSONObject(true) : new JSONObject(source);
    }

    public JSONObject toFrame() {
        JSONObject event = new JSONObject(true);
        event.put("eventId", eventId);
        event.put("eventSeq", eventSeq);
        event.put("sessionId", sessionId);
        event.put("epoch", epoch);
        event.put("turnId", turnId);
        event.put("cardId", cardId);
        event.put("payload", new JSONObject(payload));
        event.put("source", new JSONObject(source));
        JSONObject frame = new JSONObject(true);
        frame.put("schemaVersion", "1.0");
        frame.put("frameType", "event");
        frame.put("requestId", null);
        frame.put("timestamp", timestamp);
        frame.put("type", type);
        frame.put("event", event);
        return frame;
    }

    public String getGatewayId() { return gatewayId; }
    public String getEventId() { return eventId; }
    public long getEventSeq() { return eventSeq; }
    public String getSessionId() { return sessionId; }
    public long getEpoch() { return epoch; }
    public String getTurnId() { return turnId; }
    public String getCardId() { return cardId; }
    public long getTimestamp() { return timestamp; }
    public String getType() { return type; }
    public JSONObject getPayload() { return new JSONObject(payload); }
    public JSONObject getSource() { return new JSONObject(source); }
}
