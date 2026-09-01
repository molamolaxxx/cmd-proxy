package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Surface-neutral application boundary for ordinary MAIN session commands.
 * MolaChat RPC and Starweave REST keep their own transport/projection layers,
 * while lifecycle concurrency and busy-policy semantics stay shared here.
 */
public final class MainSessionApplicationService {
    private final AcpClientRegistry registry;

    public MainSessionApplicationService(AcpClientRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public AcpClient create(String groupId, String workDir, AcpRobotParam robot,
                            AcpClientRegistry.ClientInitializer initializer) throws Exception {
        return registry.createSession(groupId, workDir, robot, initializer);
    }

    public AcpClient create(AcpClientIdentity identity, String workDir,
                            AcpRobotParam robot, boolean forceNew,
                            AcpClientRegistry.ClientInitializer initializer) throws Exception {
        return registry.createSession(identity, workDir, robot, forceNew, initializer);
    }

    public PromptCommandResult send(String groupId, String message,
                                    List<Map<String, String>> files, String busyPolicy) {
        return registry.sendMessageWithResult(groupId, message, files, busyPolicy);
    }

    public PromptCommandResult cancel(String groupId) {
        return registry.cancelPromptWithResult(groupId);
    }

    public AbstractAcpClient.State status(String groupId) {
        AcpClient client = registry.getClient(groupId);
        return client == null ? null : client.getState();
    }

    public double contextUsagePercentage(String groupId) {
        AcpClient client = registry.getClient(groupId);
        return client == null ? -1D : client.getContextUsagePercentage();
    }

    public String currentSessionId(String groupId) {
        AcpClient client = registry.getClient(groupId);
        return client == null ? null : client.getSessionId();
    }

    public List<ConversationHistoryManager.SessionSummary> listSessions(
            String groupId, int limit) {
        AcpClient client = registry.getClient(groupId);
        if (client == null) return java.util.Collections.emptyList();
        return client.getHistoryManager().listRecentSessions(limit);
    }

    public AcpClient replaceIfCurrent(String groupId, AcpClient expected,
                                      String targetSessionId,
                                      AcpClientRegistry.ClientInitializer initializer)
            throws Exception {
        return registry.replaceSessionIfCurrent(
                groupId, expected, targetSessionId, initializer);
    }

    public AcpClient replaceIdleIfCurrent(String groupId, AcpClient expected,
                                          long nowMillis, long idleMillis,
                                          AcpClientRegistry.ClientInitializer initializer)
            throws Exception {
        return registry.replaceIdleSessionIfCurrent(
                groupId, expected, nowMillis, idleMillis, initializer);
    }

    public void restore(String groupId, String sessionId,
                        AcpClientRegistry.ClientInitializer initializer) throws Exception {
        registry.restoreSession(groupId, sessionId, initializer);
    }
}
