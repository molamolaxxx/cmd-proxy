package com.mola.cmd.proxy.app.acp.ability;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.*;

/**
 * 能力反思专用子 Client，继承 {@link AbstractAcpClient}。
 * <p>
 * 与 MemoryAcpClient 类似，不加载任何 MCP Server，同步阻塞模式。
 * kiro-cli 会自动加载 workspacePath 下的 skills 到上下文中。
 */
public class AbilityReflectionAcpClient extends AbstractAcpClient {

    private static final Logger logger = LoggerFactory.getLogger(AbilityReflectionAcpClient.class);

    private final int timeoutSeconds;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ability-reflection-worker");
        t.setDaemon(true);
        return t;
    });

    public AbilityReflectionAcpClient(String workspacePath, String groupId,
                                      int timeoutSeconds, String agentProviderType) {
        super(AgentProviderRouter.getInstance().resolve(agentProviderType), workspacePath, groupId);
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    protected void createSession() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", workspacePath);
        params.add("mcpServers", new JsonArray());

        JsonObject response = sendRequest("session/new", params);
        JsonObject result = response.getAsJsonObject("result");
        setSessionId(result.get("sessionId").getAsString());
        logger.info("Ability reflection session 创建成功: {}", getSessionId());
    }

    /**
     * 同步发送 prompt，阻塞等待完整响应文本。
     */
    public String sendPromptSync(String promptText) throws IOException {
        Future<String> future = executor.submit(() -> doSendPromptSync(promptText, "Ability reflection ACP"));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("能力反思超时（" + timeoutSeconds + "s）", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("能力反思失败", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("能力反思被中断", e);
        }
    }

    @Override
    public void close() throws IOException {
        executor.shutdownNow();
        super.close();
    }
}
