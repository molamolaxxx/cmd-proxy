package com.mola.cmd.proxy.app.acp.acpclient.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded best-effort delivery for RPC callbacks that are only client-side
 * projections. Failure must never terminate an ACP turn or process.
 */
public final class RpcCallbackRetry {

    private static final Logger logger = LoggerFactory.getLogger(RpcCallbackRetry.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MILLIS = 100L;

    private RpcCallbackRetry() {
    }

    public static boolean run(String description, Runnable callback) {
        java.util.Objects.requireNonNull(callback, "callback");
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                callback.run();
                if (attempt > 1) {
                    logger.info("RPC callback recovered, description={}, attempt={}",
                            description, attempt);
                }
                return true;
            } catch (RuntimeException error) {
                lastError = error;
                if (attempt < MAX_ATTEMPTS) {
                    logger.warn("RPC callback failed; retrying, description={}, attempt={}/{}, error={}",
                            description, attempt, MAX_ATTEMPTS, safeMessage(error));
                    if (!backoff(attempt)) {
                        break;
                    }
                }
            }
        }
        logger.warn("RPC callback dropped after retries; ACP processing continues,"
                        + " description={}, errorType={}, error={}",
                description, lastError == null ? "unknown"
                        : lastError.getClass().getSimpleName(),
                lastError == null ? "unknown" : safeMessage(lastError));
        return false;
    }

    private static boolean backoff(int failedAttempt) {
        try {
            Thread.sleep(INITIAL_BACKOFF_MILLIS * failedAttempt);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
