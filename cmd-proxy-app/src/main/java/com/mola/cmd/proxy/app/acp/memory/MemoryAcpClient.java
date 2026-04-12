package com.mola.cmd.proxy.app.acp.memory;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProviderRouter;
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

    public MemoryAcpClient(String workspacePath, String groupId, int timeoutSeconds, String agentProviderType) {
        super(AgentProviderRouter.getInstance().resolve(agentProviderType), workspacePath, groupId);
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
        Future<String> future = executor.submit(() -> doSendPromptSync(promptText, "Memory ACP"));
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

    @Override
    public void close() throws IOException {
        executor.shutdownNow();
        super.close();
    }
}
