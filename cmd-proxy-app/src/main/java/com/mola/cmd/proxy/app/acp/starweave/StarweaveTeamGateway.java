package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.client.provider.CmdReceiver;
import com.mola.cmd.proxy.client.resp.CmdResponseContent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Request/reply bridge from the local Starweave REST facade to the trusted
 * MolaChat Fast Team coordinator. Requests use the authenticated participant
 * transport callback; replies return to the same instance-scoped transport.
 */
public final class StarweaveTeamGateway {
    public static final String REQUEST_COMMAND = "starweaveTeamGateway";
    public static final String RESULT_COMMAND = "starweaveTeamGatewayResult";
    public static final String EVENT_COMMAND = "starweaveTeamGatewayEvent";
    public static final String READY_COMMAND = "starweaveTeamGatewayReady";

    private static final long QUERY_TIMEOUT_MILLIS = 5_000L;
    private static final long MUTATION_TIMEOUT_MILLIS = 120_000L;

    private final String instanceId;
    private final String ownerId;
    private final String transportGroup;
    private final long queryTimeoutMillis;
    private final long mutationTimeoutMillis;
    private final CallbackSender callbackSender;
    private final Map<String, CompletableFuture<JSONObject>> pending =
            new ConcurrentHashMap<>();
    private volatile boolean available;

    public StarweaveTeamGateway(String instanceId, String transportGroup) {
        this(instanceId, transportGroup, QUERY_TIMEOUT_MILLIS, MUTATION_TIMEOUT_MILLIS,
                (command, group, response) -> CmdReceiver.INSTANCE.callback(
                        command, group, response));
    }

    StarweaveTeamGateway(String instanceId, String transportGroup,
                         long queryTimeoutMillis, long mutationTimeoutMillis,
                         CallbackSender callbackSender) {
        this.instanceId = required(instanceId, "instanceId");
        this.ownerId = StarweaveIdentity.ownerId(instanceId);
        this.transportGroup = required(transportGroup, "transportGroup");
        if (queryTimeoutMillis <= 0L || mutationTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("gateway timeouts must be positive");
        }
        this.queryTimeoutMillis = queryTimeoutMillis;
        this.mutationTimeoutMillis = mutationTimeoutMillis;
        this.callbackSender = java.util.Objects.requireNonNull(
                callbackSender, "callbackSender");
    }

    public JSONObject query(String operation, JSONObject payload) {
        return request(operation, payload, queryTimeoutMillis);
    }

    public JSONObject mutate(String operation, JSONObject payload) {
        return request(operation, payload, mutationTimeoutMillis);
    }

    public Map<String, String> acceptResult(String rpcRequestId, String[] args) {
        JSONObject response = singleObject(args);
        String requestId = required(response.getString("requestId"), "requestId");
        CompletableFuture<JSONObject> future = pending.remove(requestId);
        if (future != null) future.complete(response);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("requestId", rpcRequestId == null ? "" : rpcRequestId);
        result.put("accepted", future == null ? "false" : "true");
        result.put("code", future == null ? "UNKNOWN_REQUEST" : "OK");
        return result;
    }

    public Map<String, String> acceptReady(String rpcRequestId, String[] args) {
        JSONObject ready = singleObject(args);
        if (!instanceId.equals(ready.getString("instanceId"))
                || !ownerId.equals(ready.getString("ownerChatterId"))) {
            throw new IllegalArgumentException("Starweave Team gateway identity mismatch");
        }
        available = true;
        Map<String, String> result = new LinkedHashMap<>();
        result.put("requestId", rpcRequestId == null ? "" : rpcRequestId);
        result.put("accepted", "true");
        result.put("code", "OK");
        return result;
    }

    private JSONObject request(String operation, JSONObject payload, long timeoutMillis) {
        if (!available) {
            throw new IllegalStateException("Starweave Team coordinator is unavailable");
        }
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        pending.put(requestId, future);
        Map<String, String> request = new LinkedHashMap<>();
        request.put("schemaVersion", "1");
        request.put("requestId", requestId);
        request.put("instanceId", instanceId);
        request.put("ownerChatterId", ownerId);
        request.put("operation", required(operation, "operation"));
        request.put("payload", payload == null ? "{}" : payload.toJSONString());
        try {
            callbackSender.send(REQUEST_COMMAND, transportGroup,
                    new CmdResponseContent(requestId, request));
            JSONObject response = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!response.getBooleanValue("accepted")) {
                throw new IllegalStateException(textOr(response.getString("message"),
                        "Starweave Team coordinator rejected the request"));
            }
            JSONObject data = response.getJSONObject("data");
            return data == null ? new JSONObject(true) : data;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Starweave Team coordinator request interrupted",
                    interrupted);
        } catch (java.util.concurrent.TimeoutException timeout) {
            available = false;
            throw new IllegalStateException("Starweave Team coordinator is unavailable", timeout);
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw new IllegalStateException("Starweave Team coordinator request failed: "
                    + cause.getMessage(), cause);
        } finally {
            pending.remove(requestId, future);
        }
    }

    private static JSONObject singleObject(String[] args) {
        if (args == null || args.length != 1 || args[0] == null) {
            throw new IllegalArgumentException("exactly one JSON argument is required");
        }
        JSONObject value = JSON.parseObject(args[0]);
        if (value == null) throw new IllegalArgumentException("JSON argument is required");
        return value;
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    @FunctionalInterface
    interface CallbackSender {
        void send(String command, String group, CmdResponseContent response);
    }
}
