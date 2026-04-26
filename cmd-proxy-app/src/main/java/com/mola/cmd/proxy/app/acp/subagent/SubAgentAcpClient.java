package com.mola.cmd.proxy.app.acp.subagent;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.McpConfigLoader;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 子 Agent 专用 ACP Client，继承 {@link AbstractAcpClient}。
 * <p>
 * 与 MemoryAcpClient 的关键差异：
 * <ul>
 *   <li>加载目标 robot 的 MCP Server 配置（子 Agent 可能需要工具）</li>
 *   <li>workspacePath 指向目标 robot 的工作目录</li>
 *   <li>支持定期进度快照回调，让客户端跟进执行过程</li>
 * </ul>
 */
public class SubAgentAcpClient extends AbstractAcpClient {

    private static final Logger logger = LoggerFactory.getLogger(SubAgentAcpClient.class);

    private final int timeoutSeconds;
    private final List<Path> mcpConfigPaths;
    private final ExecutorService executor;

    /** 进度快照回调间隔（毫秒） */
    private long progressIntervalMs = 30_000;

    /** 进度快照回调，由调用方设置 */
    private Consumer<String> progressCallback;

    public SubAgentAcpClient(String workspacePath, String groupId,
                             int timeoutSeconds, String agentProviderType) {
        super(AgentProviderRouter.getInstance().resolve(agentProviderType),
              workspacePath, groupId);
        this.timeoutSeconds = timeoutSeconds;
        this.mcpConfigPaths = agentProvider.getMcpConfigPaths(workspacePath);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "subagent-worker-" + groupId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 设置进度快照回调。执行过程中每隔 intervalMs 毫秒推送一次当前累积的输出快照。
     *
     * @param callback   接收进度快照文本的回调
     * @param intervalMs 推送间隔（毫秒）
     */
    public void setProgressCallback(Consumer<String> callback, long intervalMs) {
        this.progressCallback = callback;
        this.progressIntervalMs = intervalMs;
    }

    @Override
    protected void createSession() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", workspacePath);

        JsonArray mcpServers = McpConfigLoader.loadFromPaths(mcpConfigPaths);
        params.add("mcpServers", mcpServers);
        logger.info("SubAgent session/new 携带 {} 个 MCP server, workspace={}",
                mcpServers.size(), workspacePath);

        JsonObject response = sendRequest("session/new", params);
        JsonObject result = response.getAsJsonObject("result");
        setSessionId(result.get("sessionId").getAsString());
        logger.info("SubAgent session 创建成功: {}", getSessionId());
    }

    /**
     * 同步发送 prompt，阻塞等待完整响应。
     * 执行过程中定期通过 progressCallback 推送进度快照。
     */
    public String sendPromptSync(String promptText) throws IOException {
        Future<String> future = executor.submit(() -> doSendWithProgress(promptText));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("子 Agent 执行超时（" + timeoutSeconds + "s）", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("子 Agent 执行失败", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("子 Agent 执行被中断", e);
        }
    }

    /**
     * 带进度推送的 prompt 发送。
     * 在流式读取过程中同时收集文本和工具调用事件，定期推送快照。
     */
    private String doSendWithProgress(String promptText) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);

        JsonArray prompt = new JsonArray();
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", promptText);
        prompt.add(textBlock);
        params.add("prompt", prompt);

        JsonObject request = buildRequest("session/prompt", params);
        String requestId = request.get("id").getAsString();
        sendJson(request);

        StringBuilder fullResponse = new StringBuilder();
        Map<String, String> toolTitleCache = new HashMap<>();
        long lastProgressTime = System.currentTimeMillis();
        int lastProgressLength = 0;

        while (true) {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("SubAgent 进程意外关闭");
            }

            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) continue;

            JsonObject msg;
            try {
                msg = JsonParser.parseString(trimmed).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                continue;
            }

            // prompt response — turn 结束
            if (msg.has("id") && requestId.equals(msg.get("id").getAsString())) {
                return fullResponse.toString();
            }

            // session/request_permission — 自动 allow
            if (msg.has("method") && "session/request_permission".equals(msg.get("method").getAsString())) {
                autoAllowPermission(msg);
                continue;
            }

            // session/update
            if (msg.has("method") && "session/update".equals(msg.get("method").getAsString())) {
                JsonObject updateParams = msg.getAsJsonObject("params");
                if (updateParams == null) continue;
                JsonObject update = updateParams.getAsJsonObject("update");
                if (update == null) continue;

                String updateType = update.has("sessionUpdate")
                        ? update.get("sessionUpdate").getAsString() : "";

                if ("agent_message_chunk".equals(updateType)) {
                    String text = extractAgentMessageText(msg);
                    if (text != null) {
                        fullResponse.append(text);
                    }
                } else if ("tool_call".equals(updateType) || "tool_call_update".equals(updateType)) {
                    String toolCallId = update.has("toolCallId") ? update.get("toolCallId").getAsString() : "";
                    String title = update.has("title") ? update.get("title").getAsString() : "";
                    String status = update.has("status") ? update.get("status").getAsString() : "";
                    if (!title.isEmpty()) {
                        toolTitleCache.put(toolCallId, title);
                    } else {
                        title = toolTitleCache.getOrDefault(toolCallId, "");
                    }
                    if ("completed".equals(status)) {
                        fullResponse.append("\n🛠️ ").append(title).append(" ✅\n");
                    }
                }

                // 定期推送进度快照
                long now = System.currentTimeMillis();
                int currentLength = fullResponse.length();
                if (progressCallback != null
                        && (now - lastProgressTime >= progressIntervalMs)
                        && currentLength > lastProgressLength) {
                    progressCallback.accept(buildProgressSnapshot(fullResponse));
                    lastProgressTime = now;
                    lastProgressLength = currentLength;
                }
            }
        }
    }

    /**
     * 构建进度快照文本，取最后 500 字符作为预览。
     */
    private String buildProgressSnapshot(StringBuilder response) {
        String text = response.toString();
        if (text.length() > 500) {
            text = "..." + text.substring(text.length() - 500);
        }
        return text;
    }

    @Override
    public void close() throws IOException {
        executor.shutdownNow();
        super.close();
    }
}
