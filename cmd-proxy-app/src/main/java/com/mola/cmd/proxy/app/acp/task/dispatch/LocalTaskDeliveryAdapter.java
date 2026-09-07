package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.task.model.TaskException;
import com.mola.cmd.proxy.app.acp.task.service.TaskService;
import com.mola.cmd.proxy.app.acp.task.store.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable receiver used when the assignee belongs to this cmd-proxy instance. */
public final class LocalTaskDeliveryAdapter implements TaskDeliveryAdapter, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(LocalTaskDeliveryAdapter.class);
    private static final long RECEIPT_LEASE_MILLIS = 30_000L;
    private final TaskRepository receiptStore;
    private final TaskService taskAuthority;
    private final TaskPromptSink promptSink;
    private final String workerId = "task-receiver-" + java.util.UUID.randomUUID();
    private final ScheduledExecutorService recoveryExecutor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public LocalTaskDeliveryAdapter(TaskRepository receiptStore, TaskPromptSink promptSink) {
        this(receiptStore, null, promptSink);
    }

    /** Production constructor enables stale/terminal receipt suppression against current state. */
    public LocalTaskDeliveryAdapter(TaskService taskService, TaskPromptSink promptSink) {
        this(Objects.requireNonNull(taskService, "taskService").getRepository(),
                taskService, promptSink);
    }

    private LocalTaskDeliveryAdapter(TaskRepository receiptStore, TaskService taskAuthority,
                                     TaskPromptSink promptSink) {
        this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
        this.taskAuthority = taskAuthority;
        this.promptSink = Objects.requireNonNull(promptSink, "promptSink");
        this.recoveryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "starweave-task-receipt-recovery");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Starts restart/BUSY recovery. The composition root calls this after clients exist. */
    public void start() {
        if (closed.get()) throw new IllegalStateException("task receipt recovery is closed");
        if (started.compareAndSet(false, true)) {
            recoveryExecutor.scheduleWithFixedDelay(() -> {
                try {
                    recoverPending(20);
                } catch (RuntimeException failure) {
                    // The durable row remains pending; the next bounded pass retries it.
                    logger.warn("Starweave task receipt recovery pass failed", failure);
                }
            }, 0L, 1L, TimeUnit.SECONDS);
        }
    }

    @Override
    public DeliveryResult deliver(JSONObject assignee, JSONObject card, String agentPrompt) {
        String eventId = card == null ? null : card.getString("eventId");
        JSONObject payload = payload(assignee, card, agentPrompt);
        JSONObject accepted = receiptStore.acceptReceipt(eventId, payload, taskAuthority != null);
        // Claiming, rather than the accept call itself, fences concurrent local receiver workers.
        recoverPending(1);
        return DeliveryResult.accepted(accepted.getString("receipt"));
    }

    /** Replays durably accepted work after restart; duplicate external work remains at-least-once. */
    public int recoverPending(int limit) {
        List<JSONObject> pending = receiptStore.claimReceipts(workerId, limit,
                System.currentTimeMillis(), RECEIPT_LEASE_MILLIS);
        int completed = 0;
        for (JSONObject item : pending) {
            JSONObject payload = item.getJSONObject("payload");
            String eventId = item.getString("eventId");
            String leaseToken = item.getString("leaseToken");
            try {
                if (!isCurrentExecution(payload)) {
                    if (receiptStore.completeReceipt(eventId, leaseToken)) completed++;
                    continue;
                }
                if (payload != null && promptSink.submit(payload.getJSONObject("assignee"),
                        payload.getJSONObject("card"), payload.getString("agentPrompt"))) {
                    if (receiptStore.completeReceipt(eventId, leaseToken)) completed++;
                } else {
                    receiptStore.retryReceipt(eventId, leaseToken);
                }
            } catch (RuntimeException failure) {
                receiptStore.retryReceipt(eventId, leaseToken);
                logger.warn("Starweave task receipt delivery failed, eventId={}",
                        eventId, failure);
            }
        }
        return completed;
    }

    private boolean isCurrentExecution(JSONObject payload) {
        if (taskAuthority == null || payload == null) return true;
        JSONObject card = payload.getJSONObject("card");
        if (card == null) return true;
        String type = card.getString("eventType");
        String taskId = card.getString("taskId");
        if (taskId == null || taskId.trim().isEmpty()) return true;
        try {
            JSONObject current = taskAuthority.get(taskId, new JSONObject(true))
                    .getJSONObject("task");
            if (current == null) return false;
            if (!"TASK_ASSIGNED".equals(type) && !"TASK_CONTENT_CHANGED".equals(type)) return true;
            String status = current.getString("status");
            if ("SUSPENDED".equals(status) || "COMPLETED".equals(status)
                    || "CANCELLED".equals(status)) return false;
            return card.getLongValue("contentVersion")
                    >= current.getLongValue("contentVersion");
        } catch (TaskException missing) {
            if ("NOT_FOUND".equals(missing.getCode())) return false;
            throw missing;
        }
    }

    private static JSONObject payload(JSONObject assignee, JSONObject card, String prompt) {
        JSONObject payload = new JSONObject(true);
        payload.put("assignee", assignee == null ? null : new JSONObject(assignee));
        payload.put("card", card == null ? null : new JSONObject(card));
        payload.put("agentPrompt", prompt);
        return payload;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        recoveryExecutor.shutdownNow();
        try {
            recoveryExecutor.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
