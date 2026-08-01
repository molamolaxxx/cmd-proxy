package com.mola.cmd.proxy.app.acp.memory;

import com.mola.cmd.proxy.app.acp.acpclient.MemoryManagerBridge;
import com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage;
import com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

/**
 * 将上一次进程 stop 留下的记忆 pending 重新提交到当前代际的异步队列。
 *
 * <p>恢复不阻塞 client READY；pending 只有在提取真正成功后才清除。claims 用于
 * 防止共享 history namespace 的多个 client 在同一进程代际重复提交。</p>
 */
public final class PendingMemoryExtractionRecovery {

    private static final Logger logger =
            LoggerFactory.getLogger(PendingMemoryExtractionRecovery.class);

    private PendingMemoryExtractionRecovery() {
    }

    public static int recover(ConversationHistoryManager historyManager,
                              String historyNamespace,
                              String workspacePath,
                              MemoryManagerBridge memoryManager,
                              ConcurrentMap<String, String> claims) {
        Objects.requireNonNull(historyManager, "historyManager");
        Objects.requireNonNull(memoryManager, "memoryManager");
        Objects.requireNonNull(claims, "claims");
        String namespace = historyNamespace == null ? "" : historyNamespace;
        int submitted = 0;
        for (ConversationHistoryManager.PendingMemoryExtraction pending
                : historyManager.listPendingMemoryExtractions()) {
            String claimKey = namespace + ":" + pending.getSessionId();
            String claimToken = UUID.randomUUID().toString();
            if (claims.putIfAbsent(claimKey, claimToken) != null) {
                continue;
            }
            List<ContextMessage> history =
                    historyManager.getFullHistory(pending.getSessionId());
            if (history.isEmpty()) {
                logger.warn("待恢复记忆提取没有可读历史，保留 pending, sessionId={}",
                        pending.getSessionId());
                claims.remove(claimKey, claimToken);
                continue;
            }
            logger.info("提交启动后记忆恢复, sessionId={}, turnCount={}, messages={}",
                    pending.getSessionId(), pending.getTurnCount(), history.size());
            memoryManager.submitExtractFull(
                    workspacePath,
                    history,
                    () -> {
                        historyManager.clearMemoryExtractionPending(
                                pending.getSessionId(), pending.getTurnCount());
                        memoryManager.incrementSessionCount(workspacePath);
                        claims.remove(claimKey, claimToken);
                    },
                    error -> {
                        logger.warn("启动后记忆恢复失败，保留 pending, sessionId={}",
                                pending.getSessionId(), error);
                        claims.remove(claimKey, claimToken);
                    });
            submitted++;
        }
        return submitted;
    }
}
