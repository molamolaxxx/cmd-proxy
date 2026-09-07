package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONObject;

import java.util.Objects;

/** Fail-closed bridge to the trusted cross-instance Starweave coordinator. */
public final class CoordinatedTaskDeliveryAdapter implements TaskDeliveryAdapter {
    private final Gateway gateway;

    public CoordinatedTaskDeliveryAdapter(Gateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public DeliveryResult deliver(JSONObject assignee, JSONObject card, String agentPrompt) {
        if (!gateway.isAvailable()) return DeliveryResult.retry("Starweave coordinator unavailable");
        JSONObject request = new JSONObject(true);
        request.put("schemaVersion", "1");
        request.put("operation", "TASK_ACCEPT");
        request.put("eventId", card.getString("eventId"));
        request.put("assignee", new JSONObject(assignee));
        request.put("card", new JSONObject(card));
        request.put("agentPrompt", agentPrompt);
        JSONObject response = gateway.accept(request);
        if (response == null || !response.getBooleanValue("accepted")) {
            return DeliveryResult.retry(response == null
                    ? "Coordinator returned no response" : response.getString("message"));
        }
        String receipt = response.getString("receipt");
        return receipt == null || receipt.trim().isEmpty()
                ? DeliveryResult.retry("Coordinator returned no durable receipt")
                : DeliveryResult.accepted(receipt);
    }

    public interface Gateway {
        boolean isAvailable();

        /** Must return only after the execution instance persisted eventId and payload. */
        JSONObject accept(JSONObject request);
    }
}
