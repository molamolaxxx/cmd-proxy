package com.mola.cmd.proxy.app.acp.acpclient;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates user prompts for ordinary MAIN ACP clients.
 *
 * <p>Callers serialize every method for one group with the same lifecycle lock. A BUSY
 * interrupt stores only the latest prompt and never sends it until the interrupted turn has
 * completed the real BUSY -> READY transition.</p>
 */
final class MainAcpPromptCoordinator {

    interface ClientPort {
        Object identity();
        AbstractAcpClient.State state();
        void send(String message, List<Map<String, String>> files);
        void cancel() throws IOException;
        default void markNextTermination(String termination) { }
        default void clearNextTermination() { }
    }

    enum BusyPolicy {
        REJECT,
        INTERRUPT;

        static BusyPolicy from(String value) {
            return value != null && "INTERRUPT".equalsIgnoreCase(value.trim())
                    ? INTERRUPT : REJECT;
        }
    }

    private static final class PendingPrompt {
        private final Object clientIdentity;
        private final String message;
        private final List<Map<String, String>> files;

        private PendingPrompt(Object clientIdentity, String message,
                              List<Map<String, String>> files) {
            this.clientIdentity = clientIdentity;
            this.message = message;
            this.files = files;
        }
    }

    private final ConcurrentHashMap<String, PendingPrompt> pendingByGroup =
            new ConcurrentHashMap<>();

    PromptCommandResult send(String groupId, ClientPort client, String message,
                             List<Map<String, String>> files, BusyPolicy busyPolicy) {
        AbstractAcpClient.State state = client.state();
        if (state == AbstractAcpClient.State.READY) {
            // READY 事件已发生但回调尚在等待 group lock 时，新请求是最新请求。
            pendingByGroup.remove(groupId);
            client.send(message, files);
            return PromptCommandResult.accepted("SENT", "消息发送成功");
        }
        if (state != AbstractAcpClient.State.BUSY || busyPolicy != BusyPolicy.INTERRUPT) {
            return PromptCommandResult.rejected("REJECTED_STATE",
                    "当前client状态不允许发送消息: " + state);
        }

        // One BUSY turn has one terminal boundary. Later rapid submissions only replace
        // its pending prompt and must not overwrite/clear the marker owned by the first one.
        boolean firstInterrupt = !pendingByGroup.containsKey(groupId);
        if (firstInterrupt) {
            client.markNextTermination("INTERRUPTED");
        }
        try {
            client.cancel();
        } catch (IOException | RuntimeException e) {
            if (firstInterrupt) {
                client.clearNextTermination();
            }
            return PromptCommandResult.rejected(
                    "CANCEL_FAILED", "取消当前消息失败: " + e.getMessage());
        }
        pendingByGroup.put(groupId,
                new PendingPrompt(client.identity(), message, copyFiles(files)));
        return PromptCommandResult.accepted(
                "INTERRUPTED_PENDING", "已取消当前消息，等待会话就绪后发送");
    }

    PromptCommandResult cancel(ClientPort client) {
        AbstractAcpClient.State state = client.state();
        if (state == AbstractAcpClient.State.READY) {
            return PromptCommandResult.accepted("NOOP", "当前会话已就绪，无需取消");
        }
        if (state != AbstractAcpClient.State.BUSY) {
            return PromptCommandResult.rejected(
                    "REJECTED_STATE", "当前client状态不允许取消: " + state);
        }
        try {
            client.cancel();
            return PromptCommandResult.accepted("CANCELED", "已发送取消指令");
        } catch (IOException | RuntimeException e) {
            return PromptCommandResult.rejected("CANCEL_FAILED", "取消失败: " + e.getMessage());
        }
    }

    void onReady(String groupId, ClientPort client) {
        PendingPrompt pending = pendingByGroup.get(groupId);
        if (pending == null || pending.clientIdentity != client.identity()
                || client.state() != AbstractAcpClient.State.READY) {
            return;
        }
        if (!pendingByGroup.remove(groupId, pending)) return;
        client.send(pending.message, pending.files);
    }

    void clear(String groupId) {
        pendingByGroup.remove(groupId);
    }

    void clearAll() {
        pendingByGroup.clear();
    }

    boolean hasPending(String groupId) {
        return pendingByGroup.containsKey(groupId);
    }

    boolean hasPending(String groupId, ClientPort client) {
        PendingPrompt pending = pendingByGroup.get(groupId);
        return pending != null && pending.clientIdentity == client.identity();
    }

    private static List<Map<String, String>> copyFiles(List<Map<String, String>> files) {
        if (files == null) return null;
        java.util.ArrayList<Map<String, String>> copy = new java.util.ArrayList<>(files.size());
        for (Map<String, String> file : files) {
            copy.add(file == null ? null : new java.util.LinkedHashMap<>(file));
        }
        return copy;
    }
}
