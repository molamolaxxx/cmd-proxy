package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONObject;

/** Accepts a task event at its execution instance before any Agent prompt is started. */
public interface TaskDeliveryAdapter {
    DeliveryResult deliver(JSONObject assignee, JSONObject card, String agentPrompt);

    final class DeliveryResult {
        public enum Status { ACCEPTED, RETRY, DISCARD }

        private final Status status;
        private final String receipt;
        private final String error;

        private DeliveryResult(Status status, String receipt, String error) {
            this.status = status;
            this.receipt = receipt;
            this.error = error;
        }

        public static DeliveryResult accepted(String durableReceipt) {
            if (durableReceipt == null || durableReceipt.trim().isEmpty()) {
                throw new IllegalArgumentException("durableReceipt is required");
            }
            return new DeliveryResult(Status.ACCEPTED, durableReceipt.trim(), null);
        }

        public static DeliveryResult retry(String error) {
            return new DeliveryResult(Status.RETRY, null, safe(error));
        }

        public static DeliveryResult discard(String reason) {
            return new DeliveryResult(Status.DISCARD, null, safe(reason));
        }

        public Status getStatus() { return status; }
        public String getReceipt() { return receipt; }
        public String getError() { return error; }

        private static String safe(String value) {
            return value == null || value.trim().isEmpty() ? "delivery failed" : value.trim();
        }
    }
}
