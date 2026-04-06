package com.mola.cmd.proxy.app.acp.acpclient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AcpClient 注册中心，按 groupId 维护 AcpClient 实例。
 * <p>
 * 每个 groupId 对应一个独立的 AcpClient（独立子进程 + 独立会话）。
 * 提供三个核心接口：创建会话、清除会话、发送消息。
 */
public class AcpClientRegistry {

    private static final Logger logger = LoggerFactory.getLogger(AcpClientRegistry.class);

    private static final AcpClientRegistry INSTANCE = new AcpClientRegistry();

    private final ConcurrentHashMap<String, AcpClient> clients = new ConcurrentHashMap<>();

    private AcpClientRegistry() {
    }

    public static AcpClientRegistry getInstance() {
        return INSTANCE;
    }

    // ==================== 核心接口 ====================

    /**
     * 创建会话：为指定 groupId 创建并启动一个 AcpClient。
     * 如果该 groupId 已有 client，会先关闭旧的再创建新的。
     *
     * @param groupId       分组标识
     * @param workspacePath 工作目录
     * @throws IOException 启动失败时抛出
     */
    public void createSession(String groupId, String workspacePath) throws IOException {
        // 如果已存在，先关闭旧 client
        AcpClient old = clients.remove(groupId);
        if (old != null) {
            logger.info("groupId={} 已有 client，先关闭旧实例", groupId);
            try {
                old.close();
            } catch (IOException e) {
                logger.warn("关闭旧 AcpClient 失败, groupId={}", groupId, e);
            }
            workspacePath = old.getWorkspacePath();
        }

        AcpClient client = new AcpClient(workspacePath, groupId);
        client.start();
        clients.put(groupId, client);
        logger.info("groupId={} 会话创建成功, sessionId={}", groupId, client.getSessionId());
    }

    /**
     * 发送消息：向指定 groupId 的 AcpClient 发送用户消息，可附带图片。
     */
    public void sendMessage(String groupId, String message, List<String> imageBase64List) {
        AcpClient client = clients.get(groupId);
        if (client == null) {
            throw new IllegalStateException("groupId=" + groupId + " 的会话不存在，请先调用 createSession");
        }
        client.send(message, imageBase64List);
    }

    /**
     * 取消指定 groupId 当前正在进行的 prompt turn。
     */
    public void cancelPrompt(String groupId) throws IOException {
        AcpClient client = clients.get(groupId);
        if (client == null) {
            throw new IllegalStateException("groupId=" + groupId + " 的会话不存在，请先调用 createSession");
        }
        client.cancel();
    }

    /**
     * 获取指定 groupId 的 AcpClient（可能为 null）
     */
    public AcpClient getClient(String groupId) {
        return clients.get(groupId);
    }
}
