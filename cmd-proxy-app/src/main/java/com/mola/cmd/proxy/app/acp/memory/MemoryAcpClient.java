package com.mola.cmd.proxy.app.acp.memory;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.*;

/**
 * 记忆提取专用子 Client，继承 {@link AbstractAcpClient}。
 * <p>
 * 与主 AcpClient 的关键差异：
 * <ul>
 *   <li>不加载任何 MCP Server，加速启动</li>
 *   <li>同步阻塞模式，返回完整响应文本</li>
 *   <li>内置超时控制</li>
 *   <li>无图片处理、无会话历史、无 listener 回调</li>
 * </ul>
 */
public class MemoryAcpClient extends AbstractAcpClient {

    private static final Logger logger = LoggerFactory.getLogger(MemoryAcpClient.class);

    private final int timeoutSeconds;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memory-acp-worker");
        t.setDaemon(true);
        return t;
    });

    public MemoryAcpClient(String workspacePath, String groupId, int timeoutSeconds) {
        super(workspacePath, groupId);
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    protected void createSession() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", workspacePath);
        params.add("mcpServers", new JsonArray());  // 不加载任何 MCP

        JsonObject response = sendRequest("session/new", params);
        JsonObject result = response.getAsJsonObject("result");
        setSessionId(result.get("sessionId").getAsString());
        logger.info("Memory session 创建成功: {}", getSessionId());
    }

    /**
     * 同步发送 prompt，阻塞等待完整响应文本。
     *
     * @param promptText 发送给子 Client 的完整 prompt
     * @return agent 的完整回答文本
     * @throws IOException 通信失败或超时
     */
    public String sendPromptSync(String promptText) throws IOException {
        Future<String> future = executor.submit(() -> doSendPrompt(promptText));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("记忆提取超时（" + timeoutSeconds + "s）", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("记忆提取失败", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("记忆提取被中断", e);
        }
    }

    private String doSendPrompt(String promptText) throws IOException {
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
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("Memory ACP 进程意外关闭");
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
                String permId = msg.has("id") ? msg.get("id").getAsString() : null;
                if (permId != null) {
                    JsonObject outcomeObj = new JsonObject();
                    outcomeObj.addProperty("outcome", "selected");
                    outcomeObj.addProperty("optionId", "allow_always");
                    JsonObject permResult = new JsonObject();
                    permResult.add("outcome", outcomeObj);
                    JsonObject permResp = new JsonObject();
                    permResp.addProperty("jsonrpc", JSONRPC_VERSION);
                    permResp.addProperty("id", permId);
                    permResp.add("result", permResult);
                    sendJson(permResp);
                }
                continue;
            }

            // session/update — 拼接文本
            if (msg.has("method") && "session/update".equals(msg.get("method").getAsString())) {
                JsonObject updateParams = msg.getAsJsonObject("params");
                if (updateParams == null) continue;
                JsonObject update = updateParams.getAsJsonObject("update");
                if (update == null) continue;

                String updateType = update.has("sessionUpdate")
                        ? update.get("sessionUpdate").getAsString() : "";
                if ("agent_message_chunk".equals(updateType)) {
                    JsonObject content = update.getAsJsonObject("content");
                    if (content != null && content.has("text")) {
                        fullResponse.append(content.get("text").getAsString());
                    }
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        executor.shutdownNow();
        super.close();
    }
}
