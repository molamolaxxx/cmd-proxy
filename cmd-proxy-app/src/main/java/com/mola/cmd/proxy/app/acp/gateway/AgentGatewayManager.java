package com.mola.cmd.proxy.app.acp.gateway;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.gateway.model.AgentGatewayConfig;
import com.mola.cmd.proxy.app.acp.gateway.model.AgentGatewayServerConfig;
import com.mola.cmd.proxy.app.acp.gateway.model.GatewayEvent;
import com.mola.cmd.proxy.app.acp.gateway.model.GatewayTarget;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns logical gateway authentication, session guards, event durability and subscribers. */
public final class AgentGatewayManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(AgentGatewayManager.class);
    private final AgentGatewayServerConfig serverConfig;
    private final GatewayRuntime runtime;
    private final GatewayEventJournal journal;
    private final Map<String, AgentGatewayConfig> gateways = new LinkedHashMap<>();
    private final Map<String, AgentGatewayConfig> tokens = new LinkedHashMap<>();
    private final Map<String, Cursor> cursors = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Subscriber>> subscribers =
            new ConcurrentHashMap<>();
    private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();
    private final AtomicInteger totalSubscribers = new AtomicInteger();
    private volatile boolean degraded;
    private volatile String lastError;

    public AgentGatewayManager(AgentGatewayServerConfig serverConfig,
                               Collection<AgentGatewayConfig> configs,
                               GatewayRuntime runtime) throws Exception {
        this(serverConfig, configs, runtime, new GatewayEventJournal(
                CmdProxyHome.resolve("agent-gateway/agent-gateway.db")));
    }

    AgentGatewayManager(AgentGatewayServerConfig serverConfig,
                        Collection<AgentGatewayConfig> configs,
                        GatewayRuntime runtime,
                        GatewayEventJournal journal) throws Exception {
        this.serverConfig = serverConfig == null ? new AgentGatewayServerConfig() : serverConfig;
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
        this.journal = java.util.Objects.requireNonNull(journal, "journal");
        java.util.HashSet<String> targetKeys = new java.util.HashSet<>();
        java.util.HashSet<String> authCodes = new java.util.HashSet<>();
        if (configs != null) {
            for (AgentGatewayConfig config : configs) {
                if (config == null) continue;
                config.validate(CmdProxyHome.instanceId());
                if (gateways.put(config.getId(), config) != null) {
                    throw new IllegalArgumentException("duplicate Agent gateway id: " + config.getId());
                }
                if (!authCodes.add(config.getAuthCode())) {
                    throw new IllegalArgumentException("duplicate Agent gateway auth code");
                }
                tokens.put(config.getAuthCode(), config);
                if (config.isEnabled() && !targetKeys.add(config.getTarget().key())) {
                    throw new IllegalArgumentException(
                            "only one enabled Agent gateway may bind the same target");
                }
            }
        }
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(
                this.serverConfig.getEventRetentionDays());
        journal.purgeOlderThan(cutoff);
    }

    public AgentGatewayServerConfig getServerConfig() { return serverConfig; }

    public AgentGatewayConfig authenticate(String authorization) {
        String token = bearer(authorization);
        AgentGatewayConfig matched = null;
        for (Map.Entry<String, AgentGatewayConfig> entry : tokens.entrySet()) {
            if (constantTimeEquals(token, entry.getKey())) matched = entry.getValue();
        }
        if (matched == null) {
            throw new GatewayException(401, "INVALID_TOKEN", "Invalid Bearer token", false);
        }
        if (!matched.isEnabled()) {
            throw new GatewayException(403, "GATEWAY_DISABLED", "Agent gateway is disabled", false);
        }
        if (degraded) {
            throw new GatewayException(503, "EVENT_STORE_UNAVAILABLE",
                    lastError == null ? "Agent gateway is degraded" : lastError, true);
        }
        return matched;
    }

    public void admitRequest(AgentGatewayConfig gateway) {
        long minute = System.currentTimeMillis() / 60_000L;
        RateWindow window = rateWindows.computeIfAbsent(gateway.getId(), ignored -> new RateWindow());
        synchronized (window) {
            if (window.minute != minute) { window.minute = minute; window.count = 0; }
            if (++window.count > gateway.getLimits().getRequestsPerMinute()) {
                throw new GatewayException(429, "RATE_LIMITED", "Gateway request limit exceeded", true);
            }
        }
    }

    public JSONObject status(AgentGatewayConfig gateway) {
        JSONObject nativeStatus = runtime.status(gateway.getTarget());
        String sessionId = requiredText(nativeStatus, "sessionId", "TARGET_UNAVAILABLE");
        Cursor cursor = cursor(gateway, sessionId);
        JSONObject session = new JSONObject(true);
        session.put("sessionId", sessionId);
        session.put("epoch", cursor.epoch);
        session.put("state", nativeStatus.getString("state"));
        session.put("contextUsagePercentage", nativeStatus.getDouble("contextUsagePercentage"));
        session.put("currentTurnId", cursor.currentTurnId);
        try { session.put("lastEventSeq", journal.lastSeq(
                gateway.getId(), sessionId, cursor.epoch)); }
        catch (Exception e) { failJournal(e); }
        session.put("updatedAt", System.currentTimeMillis());

        JSONObject gatewayValue = new JSONObject(true);
        gatewayValue.put("id", gateway.getId());
        gatewayValue.put("name", gateway.getName());
        gatewayValue.put("status", degraded ? "DEGRADED" : "RUNNING");
        JSONObject target = new JSONObject(true);
        target.put("type", gateway.getTarget().getType());
        target.put("displayName", nativeStatus.getString("displayName"));
        target.put("surface", nativeStatus.getString("surface"));
        target.put("capabilities", java.util.Arrays.asList(
                "SEND", "CANCEL", "NEW_SESSION", "EVENT_REPLAY"));
        JSONObject data = new JSONObject(true);
        data.put("gateway", gatewayValue);
        data.put("target", target);
        data.put("session", session);
        return ok("OK", "Session available", data);
    }

    public JSONObject send(AgentGatewayConfig gateway, JSONObject request,
                           List<Map<String, String>> files, String idempotencyKey) {
        preflightSend(gateway, request);
        String fingerprint = digest(request.toJSONString());
        try {
            return journal.idempotent(gateway.getId(), "message.send", idempotencyKey,
                    fingerprint, () -> doSend(gateway, request, files));
        } catch (GatewayException e) { throw e; }
        catch (Exception e) { throw failJournal(e); }
    }

    /** Cheap session and payload guards that must run before any remote attachment download. */
    public void preflightSend(AgentGatewayConfig gateway, JSONObject request) {
        validateExpected(gateway, request);
        validateMessageRequest(request);
    }

    private JSONObject doSend(AgentGatewayConfig gateway, JSONObject request,
                              List<Map<String, String>> files) {
        String message = request.getString("message");
        if ((message == null || message.trim().isEmpty()) && (files == null || files.isEmpty())) {
            throw new GatewayException(400, "INVALID_ARGUMENT",
                    "message or attachments is required", false);
        }
        JSONObject nativeStatus = runtime.status(gateway.getTarget());
        Cursor cursor = cursor(gateway, nativeStatus.getString("sessionId"));
        String turnId = "turn_" + UUID.randomUUID().toString();
        boolean interrupt = "INTERRUPT".equalsIgnoreCase(request.getString("busyPolicy"));
        synchronized (cursor) {
            boolean busy = cursor.currentTurnId != null
                    || "BUSY".equalsIgnoreCase(nativeStatus.getString("state"));
            if (busy && !interrupt) {
                throw new GatewayException(409, "SESSION_BUSY", "Session is busy", true);
            }
            if (busy) {
                if (cursor.currentTurnId == null) {
                    cursor.currentTurnId = "turn_observed_" + UUID.randomUUID().toString();
                }
                cursor.pendingTurnId = turnId;
            } else {
                cursor.currentTurnId = turnId;
            }
            cursor.cancelRequested = false;
        }
        JSONObject result;
        try {
            result = runtime.send(gateway.getTarget(), message == null ? "" : message,
                    files, request.getString("busyPolicy"));
        } catch (RuntimeException failure) {
            synchronized (cursor) {
                if (turnId.equals(cursor.currentTurnId)) cursor.currentTurnId = null;
                if (turnId.equals(cursor.pendingTurnId)) cursor.pendingTurnId = null;
            }
            throw failure;
        }
        if (!result.getBooleanValue("accepted")) {
            synchronized (cursor) {
                if (turnId.equals(cursor.currentTurnId)) cursor.currentTurnId = null;
                if (turnId.equals(cursor.pendingTurnId)) cursor.pendingTurnId = null;
            }
            throw new GatewayException(409, result.getString("code"),
                    result.getString("message"), true);
        }
        JSONObject payload = new JSONObject(true);
        payload.put("text", message == null ? "" : message);
        payload.put("attachments", request.getJSONArray("attachments") == null
                ? new JSONArray() : sanitizedAttachments(request.getJSONArray("attachments")));
        payload.put("metadata", request.getJSONObject("metadata"));
        append(gateway, cursor, turnId, null, "user.message.accepted", payload,
                source(gateway.getTarget(), "USER_MESSAGE_ACCEPTED", payload));
        JSONObject data = new JSONObject(true);
        data.put("sessionId", cursor.sessionId);
        data.put("epoch", cursor.epoch);
        data.put("turnId", turnId);
        data.put("admission", result.getString("code"));
        return ok("PROMPT_ACCEPTED", "Message accepted", data);
    }

    public JSONObject cancel(AgentGatewayConfig gateway, JSONObject request,
                             String idempotencyKey) {
        validateExpected(gateway, request);
        String fingerprint = digest(request.toJSONString());
        try {
            return journal.idempotent(gateway.getId(), "session.cancel", idempotencyKey,
                    fingerprint, () -> {
                        Cursor cursor = cursor(gateway, request.getString("expectedSessionId"));
                        String expectedTurnId = request.getString("expectedTurnId");
                        synchronized (cursor) {
                            if (cursor.currentTurnId == null || (expectedTurnId != null
                                    && !expectedTurnId.equals(cursor.currentTurnId))) {
                                throw new GatewayException(404, "TURN_NOT_RUNNING",
                                        "The requested turn is not running", false);
                            }
                            cursor.cancelRequested = true;
                        }
                        JSONObject result = runtime.cancel(gateway.getTarget());
                        if (!result.getBooleanValue("accepted")) {
                            throw new GatewayException(409, result.getString("code"),
                                    result.getString("message"), true);
                        }
                        JSONObject data = new JSONObject(true);
                        data.put("sessionId", cursor.sessionId);
                        data.put("epoch", cursor.epoch);
                        data.put("turnId", cursor.currentTurnId);
                        return ok("CANCEL_REQUESTED", "Cancellation requested", data);
                    });
        } catch (GatewayException e) { throw e; }
        catch (Exception e) { throw failJournal(e); }
    }

    public JSONObject newSession(AgentGatewayConfig gateway, JSONObject request,
                                 String idempotencyKey) {
        validateExpected(gateway, request);
        String fingerprint = digest(request.toJSONString());
        try {
            return journal.idempotent(gateway.getId(), "session.new", idempotencyKey,
                    fingerprint, () -> {
                        Cursor previous = cursor(gateway, request.getString("expectedSessionId"));
                        if (previous.currentTurnId != null) {
                            throw new GatewayException(409, "SESSION_BUSY",
                                    "Cancel the current turn before creating a new session", true);
                        }
                        JSONObject result = runtime.newSession(gateway.getTarget());
                        if (!result.getBooleanValue("accepted")) {
                            throw new GatewayException(409, result.getString("code"),
                                    result.getString("message"), true);
                        }
                        JSONObject nativeStatus = runtime.status(gateway.getTarget());
                        String nextSessionId = requiredText(nativeStatus, "sessionId", "TARGET_UNAVAILABLE");
                        Cursor next = cursors.compute(gateway.getId(), (key, old) ->
                                new Cursor(nextSessionId, old == null ? 1L : old.epoch + 1L));
                        JSONObject payload = new JSONObject(true);
                        payload.put("previousSessionId", previous.sessionId);
                        payload.put("sessionId", next.sessionId);
                        payload.put("previousEpoch", previous.epoch);
                        payload.put("epoch", next.epoch);
                        payload.put("reason", "MANUAL_NEW");
                        append(gateway, next, null, null, "session.replaced", payload,
                                source(gateway.getTarget(), "SESSION_REPLACED", payload));
                        JSONObject data = new JSONObject(true);
                        data.put("previousSessionId", previous.sessionId);
                        data.put("sessionId", next.sessionId);
                        data.put("epoch", next.epoch);
                        data.put("state", nativeStatus.getString("state"));
                        return ok("SESSION_CREATED", "New session created", data);
                    });
        } catch (GatewayException e) { throw e; }
        catch (Exception e) { throw failJournal(e); }
    }

    public JSONArray events(AgentGatewayConfig gateway, String sessionId, long epoch,
                            long afterSeq, int limit) {
        try { return journal.list(gateway.getId(), sessionId, epoch, afterSeq, limit); }
        catch (Exception e) { throw failJournal(e); }
    }

    public AutoCloseable subscribe(AgentGatewayConfig gateway, Subscriber subscriber) {
        final CopyOnWriteArrayList<Subscriber> values;
        synchronized (subscribers) {
            values = subscribers.computeIfAbsent(
                    gateway.getId(), ignored -> new CopyOnWriteArrayList<>());
            if (values.size() >= gateway.getLimits().getMaxConnections()
                    || totalSubscribers.get() >= serverConfig.getMaxConnections()) {
                throw new GatewayException(429, "RATE_LIMITED", "Gateway connection limit exceeded", true);
            }
            values.add(subscriber);
            totalSubscribers.incrementAndGet();
        }
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true) && values.remove(subscriber)) {
                totalSubscribers.decrementAndGet();
            }
        };
    }

    public void publish(AcpClient client, String eventType, String cardId,
                        JSONObject payload, JSONObject nativePayload) {
        AcpClientIdentity identity = client.getClientIdentity();
        for (AgentGatewayConfig gateway : gateways.values()) {
            if (!gateway.isEnabled() || !gateway.getTarget().matches(identity)) continue;
            String sessionId = client.getSessionId();
            if (sessionId == null || sessionId.trim().isEmpty()) continue;
            Cursor cursor = cursor(gateway, sessionId);
            String effectiveType = eventType;
            String turnId;
            synchronized (cursor) {
                if (cursor.currentTurnId == null && !eventType.startsWith("session.")
                        && client.getState() == com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient.State.BUSY) {
                    cursor.currentTurnId = "turn_" + UUID.randomUUID().toString();
                }
                turnId = cursor.currentTurnId;
                if ("turn.completed".equals(eventType) && cursor.cancelRequested) {
                    effectiveType = "turn.cancelled";
                    payload.put("finishReason", "cancelled");
                    payload.put("requestedBy", "external");
                }
                if (effectiveType.startsWith("turn.")) {
                    cursor.currentTurnId = cursor.pendingTurnId;
                    cursor.pendingTurnId = null;
                    cursor.cancelRequested = false;
                }
            }
            append(gateway, cursor, turnId, cardId, effectiveType, payload,
                    source(identity, nativeType(eventType), nativePayload));
        }
    }

    public void publishTeam(TeamEventEnvelope event) {
        for (AgentGatewayConfig gateway : gateways.values()) {
            GatewayTarget target = gateway.getTarget();
            if (!gateway.isEnabled() || !GatewayTarget.TEAM_MEMBER.equals(target.getType())
                    || !target.getTeamId().equals(event.getTeamId())
                    || !target.getTeamMemberId().equals(event.getTeamMemberId())) continue;
            JSONObject nativeStatus;
            try { nativeStatus = runtime.status(target); }
            catch (RuntimeException unavailable) { continue; }
            String sessionId = nativeStatus.getString("sessionId");
            if (sessionId == null || sessionId.trim().isEmpty()) continue;
            Cursor cursor = cursor(gateway, sessionId);
            JSONObject payload = JSON.parseObject(JSON.toJSONString(event.getData()));
            String type = normalizeTeamType(event.getType().name());
            String cardId = teamCardId(event.getType().name(), payload);
            String turnId;
            synchronized (cursor) {
                if (cursor.currentTurnId == null && !type.startsWith("team.")
                        && !type.startsWith("session.")
                        && "BUSY".equalsIgnoreCase(nativeStatus.getString("state"))) {
                    cursor.currentTurnId = "turn_" + UUID.randomUUID();
                }
                turnId = cursor.currentTurnId;
                if ("turn.completed".equals(type) && cursor.cancelRequested) {
                    type = "turn.cancelled";
                    payload.put("finishReason", "cancelled");
                    payload.put("requestedBy", "external");
                }
                if (type.startsWith("turn.")) {
                    cursor.currentTurnId = cursor.pendingTurnId;
                    cursor.pendingTurnId = null;
                    cursor.cancelRequested = false;
                }
            }
            JSONObject source = new JSONObject(true);
            source.put("surface", "TEAM");
            source.put("nativeType", event.getType().name());
            source.put("nativeEventId", event.getEventId());
            source.put("nativePayload", payload);
            append(gateway, cursor, turnId, cardId, type, payload, source);
        }
    }

    public JSONArray targetCatalog() { return runtime.targets(); }

    public JSONObject adminStatus() {
        JSONObject value = new JSONObject(true);
        value.put("enabled", serverConfig.isEnabled());
        value.put("bindHost", serverConfig.getBindHost());
        value.put("port", serverConfig.getPort());
        value.put("status", degraded ? "DEGRADED" : "RUNNING");
        value.put("error", lastError);
        JSONObject counts = new JSONObject(true);
        for (Map.Entry<String, AgentGatewayConfig> item : gateways.entrySet()) {
            CopyOnWriteArrayList<Subscriber> list = subscribers.get(item.getKey());
            counts.put(item.getKey(), list == null ? 0 : list.size());
        }
        value.put("connections", counts);
        return value;
    }

    private void validateExpected(AgentGatewayConfig gateway, JSONObject request) {
        if (request == null) throw new GatewayException(400, "INVALID_ARGUMENT", "JSON body is required", false);
        JSONObject status = runtime.status(gateway.getTarget());
        String actual = requiredText(status, "sessionId", "TARGET_UNAVAILABLE");
        Cursor cursor = cursor(gateway, actual);
        String expected = request.getString("expectedSessionId");
        Long epoch = request.getLong("expectedEpoch");
        if (!actual.equals(expected) || epoch == null || epoch.longValue() != cursor.epoch) {
            throw new GatewayException(409, "SESSION_CONFLICT",
                    "The current session has changed", false);
        }
    }

    private static void validateMessageRequest(JSONObject request) {
        String message = request.getString("message");
        if (message != null && message.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) {
            throw new GatewayException(413, "MESSAGE_TOO_LARGE",
                    "message exceeds the 1 MiB limit", false);
        }
        String busyPolicy = request.getString("busyPolicy");
        if (busyPolicy != null && !busyPolicy.trim().isEmpty()
                && !"REJECT".equalsIgnoreCase(busyPolicy)
                && !"INTERRUPT".equalsIgnoreCase(busyPolicy)) {
            throw new GatewayException(400, "INVALID_ARGUMENT",
                    "busyPolicy must be REJECT or INTERRUPT", false);
        }
        Object metadataValue = request.get("metadata");
        if (metadataValue != null && !(metadataValue instanceof JSONObject)) {
            throw new GatewayException(400, "INVALID_ARGUMENT",
                    "metadata must be a JSON object", false);
        }
        JSONObject metadata = (JSONObject) metadataValue;
        if (metadata != null && metadata.toJSONString().getBytes(StandardCharsets.UTF_8).length > 8192) {
            throw new GatewayException(413, "MESSAGE_TOO_LARGE",
                    "metadata exceeds the 8 KiB limit", false);
        }
    }

    private Cursor cursor(AgentGatewayConfig gateway, String sessionId) {
        String key = gateway.getId();
        Cursor current = cursors.get(key);
        if (current != null) {
            if (current.sessionId.equals(sessionId)) return current;
            return cursors.compute(key, (ignored, existing) -> existing != null
                    && existing.sessionId.equals(sessionId) ? existing
                    : new Cursor(sessionId, existing == null ? 1L : existing.epoch + 1L));
        }
        final JSONObject persisted;
        try { persisted = journal.latestPosition(gateway.getId()); }
        catch (Exception failure) { throw failJournal(failure); }
        final long initialEpoch = persisted == null ? 1L
                : persisted.getLongValue("epoch")
                + (sessionId.equals(persisted.getString("sessionId")) ? 0L : 1L);
        return cursors.compute(key, (ignored, existing) -> {
            if (existing == null) return new Cursor(sessionId, initialEpoch);
            if (!existing.sessionId.equals(sessionId)) {
                return new Cursor(sessionId, existing.epoch + 1L);
            }
            return existing;
        });
    }

    private void append(AgentGatewayConfig gateway, Cursor cursor, String turnId,
                        String cardId, String type, JSONObject payload, JSONObject source) {
        try {
            GatewayEvent event = journal.append(gateway.getId(), cursor.sessionId,
                    cursor.epoch, turnId, cardId, type, payload, source);
            CopyOnWriteArrayList<Subscriber> current = subscribers.get(gateway.getId());
            if (current != null) {
                for (Subscriber subscriber : current) {
                    try { subscriber.onEvent(event); }
                    catch (RuntimeException failed) {
                        logger.debug("Agent gateway subscriber rejected event: gatewayId={}, eventId={}",
                                gateway.getId(), event.getEventId(), failed);
                    }
                }
            }
        } catch (Exception e) {
            throw failJournal(e);
        }
    }

    private GatewayException failJournal(Exception error) {
        degraded = true;
        lastError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        logger.error("Agent gateway event journal failed; new traffic will fail closed", error);
        return new GatewayException(503, "EVENT_STORE_UNAVAILABLE", lastError, true);
    }

    private static JSONObject source(GatewayTarget target, String nativeType, JSONObject payload) {
        JSONObject value = new JSONObject(true);
        value.put("surface", target.getType());
        value.put("nativeType", nativeType);
        value.put("nativeEventId", payload == null ? null : payload.getString("eventId"));
        value.put("nativePayload", payload == null ? new JSONObject(true) : payload);
        return value;
    }

    private static JSONObject source(AcpClientIdentity identity, String nativeType,
                                     JSONObject payload) {
        JSONObject value = new JSONObject(true);
        value.put("surface", identity.getSurface().name());
        value.put("nativeType", nativeType);
        value.put("nativeEventId", payload == null ? null : payload.getString("eventId"));
        value.put("nativePayload", payload == null ? new JSONObject(true) : payload);
        return value;
    }

    private static String nativeType(String normalized) {
        return normalized == null ? "UNKNOWN" : normalized.toUpperCase(java.util.Locale.ROOT)
                .replace('.', '_');
    }

    private static String normalizeTeamType(String type) {
        if ("MESSAGE_CHUNK".equals(type)) return "assistant.message.delta";
        if ("MESSAGE_COMPLETE".equals(type)) return "turn.completed";
        if ("MESSAGE_ERROR".equals(type)) return "turn.error";
        if ("TOOL_CALL".equals(type)) return "tool_call.updated";
        if ("SUB_AGENT_EVENT".equals(type)) return "sub_agent.updated";
        if ("SCHEDULE_EVENT".equals(type)) return "schedule.updated";
        if (type != null && type.startsWith("TALK_TO_")) return "talk_to.updated";
        if ("COMPACTION_EVENT".equals(type)) return "compaction.completed";
        if ("TASK_EVENT".equals(type)) return "task.updated";
        if ("MEMBER_SESSION_CHANGED".equals(type)) return "session.replaced";
        if ("MEMBER_STATE_CHANGED".equals(type)) return "team.member.state.changed";
        if (type != null && type.startsWith("TEAM_")) return "team.state.changed";
        return "extension.event";
    }

    private static String teamCardId(String nativeType, JSONObject payload) {
        if ("TOOL_CALL".equals(nativeType)) return payload.getString("toolCallId");
        if ("TASK_EVENT".equals(nativeType)) return "task-" + payload.getString("taskId");
        if ("SUB_AGENT_EVENT".equals(nativeType) && payload.getString("agentName") != null) {
            return "subagent-" + payload.getString("agentName");
        }
        return null;
    }

    private static JSONArray sanitizedAttachments(JSONArray input) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < input.size(); i++) {
            JSONObject original = input.getJSONObject(i);
            if (original == null) continue;
            JSONObject value = new JSONObject(true);
            value.put("name", original.getString("name"));
            value.put("mediaType", original.getString("mediaType"));
            value.put("size", original.getLong("size"));
            value.put("sha256", original.getString("sha256"));
            result.add(value);
        }
        return result;
    }

    private static JSONObject ok(String code, String message, Object data) {
        JSONObject result = new JSONObject(true);
        result.put("schemaVersion", "1.0");
        result.put("requestId", "req_" + UUID.randomUUID().toString());
        result.put("accepted", true);
        result.put("code", code);
        result.put("message", message);
        result.put("timestamp", System.currentTimeMillis());
        result.put("data", data);
        return result;
    }

    public static JSONObject error(GatewayException error, String requestId) {
        JSONObject result = new JSONObject(true);
        result.put("schemaVersion", "1.0");
        result.put("requestId", requestId == null ? "req_" + UUID.randomUUID() : requestId);
        result.put("accepted", false);
        result.put("code", error.getCode());
        result.put("message", error.getMessage());
        result.put("timestamp", System.currentTimeMillis());
        result.put("retryable", error.isRetryable());
        return result;
    }

    private static String bearer(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return "";
        return authorization.substring(7).trim();
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format("%02x", b & 0xff));
            return result.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String requiredText(JSONObject value, String key, String code) {
        String result = value == null ? null : value.getString(key);
        if (result == null || result.trim().isEmpty()) {
            throw new GatewayException(503, code, "Agent target is unavailable", true);
        }
        return result;
    }

    @Override public void close() {
        GatewayEventBridge.clear(this);
        for (CopyOnWriteArrayList<Subscriber> list : subscribers.values()) {
            for (Subscriber subscriber : list) {
                try { subscriber.onClose("SERVER_SHUTDOWN"); } catch (RuntimeException ignored) { }
            }
            list.clear();
        }
        subscribers.clear();
        totalSubscribers.set(0);
        journal.close();
    }

    public interface Subscriber {
        void onEvent(GatewayEvent event);
        default void onClose(String reason) { }
    }

    private static final class Cursor {
        final String sessionId;
        final long epoch;
        String currentTurnId;
        String pendingTurnId;
        boolean cancelRequested;
        Cursor(String sessionId, long epoch) { this.sessionId = sessionId; this.epoch = epoch; }
    }

    private static final class RateWindow { long minute; int count; }
}
