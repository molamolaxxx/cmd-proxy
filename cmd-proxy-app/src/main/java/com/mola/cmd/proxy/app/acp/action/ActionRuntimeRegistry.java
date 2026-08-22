package com.mola.cmd.proxy.app.acp.action;

import com.google.gson.JsonObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/** Routes the built-in cmd-proxy MCP server to the owning ACP client. */
public final class ActionRuntimeRegistry {

    @FunctionalInterface
    public interface ToolExecutor {
        String execute(String toolName, JsonObject arguments) throws Exception;
    }

    private static final ActionRuntimeRegistry INSTANCE = new ActionRuntimeRegistry();
    private static final class RuntimeEntry {
        private final ToolExecutor executor;
        private final Supplier<Set<String>> availableTools;

        private RuntimeEntry(ToolExecutor executor, Supplier<Set<String>> availableTools) {
            this.executor = executor;
            this.availableTools = availableTools;
        }
    }

    private final ConcurrentHashMap<String, RuntimeEntry> runtimes = new ConcurrentHashMap<>();

    private ActionRuntimeRegistry() {
    }

    public static ActionRuntimeRegistry getInstance() {
        return INSTANCE;
    }

    public void register(String authSessionId, ToolExecutor executor) {
        register(authSessionId, executor, Collections::emptySet);
    }

    public void register(String authSessionId, ToolExecutor executor,
                         Supplier<Set<String>> availableTools) {
        if (authSessionId == null || executor == null) return;
        RuntimeEntry previous = runtimes.putIfAbsent(authSessionId,
                new RuntimeEntry(executor,
                        availableTools == null ? Collections::emptySet : availableTools));
        if (previous != null) {
            throw new IllegalStateException("AUTH_SESSION_ALREADY_REGISTERED");
        }
    }

    public void unregister(String authSessionId) {
        if (authSessionId != null) runtimes.remove(authSessionId);
    }

    public Set<String> availableTools(String authSessionId) {
        RuntimeEntry entry = requireRuntime(authSessionId);
        Set<String> tools = entry.availableTools.get();
        return tools == null ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(tools));
    }

    public String execute(String authSessionId, String toolName, JsonObject arguments)
            throws Exception {
        RuntimeEntry entry = requireRuntime(authSessionId);
        if (!availableTools(authSessionId).contains(toolName)) {
            throw new IllegalStateException("TOOL_NOT_AVAILABLE: " + toolName);
        }
        return entry.executor.execute(toolName,
                arguments == null ? new JsonObject() : arguments);
    }

    private RuntimeEntry requireRuntime(String authSessionId) {
        if (authSessionId == null || authSessionId.trim().isEmpty()) {
            throw new IllegalStateException("AUTH_CONTEXT_MISSING");
        }
        RuntimeEntry entry = runtimes.get(authSessionId);
        if (entry == null) throw new IllegalStateException("AUTH_SESSION_NOT_FOUND");
        return entry;
    }
}
