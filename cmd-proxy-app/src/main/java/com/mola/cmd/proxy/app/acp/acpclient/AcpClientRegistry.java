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

    private String defaultCommand = System.getProperty("user.home") + "/.local/bin/kiro-cli";
    private String[] defaultArgs = {"acp"};

    private AcpClientRegistry() {
    }

    public static AcpClientRegistry getInstance() {
        return INSTANCE;
    }

    // ==================== 配置 ====================

    public String getDefaultCommand() {
        return defaultCommand;
    }

    // ==================== 核心接口 ====================

    /**
     * 创建会话：为指定 groupId 创建并启动一个 AcpClient。
     * 如果该 groupId 已有 client，会先关闭旧的再创建新的。
     *
     * @param groupId 分组标识
     * @throws IOException 启动失败时抛出
     */
    public void createSession(String groupId, String workDir) throws IOException {
        createSession(groupId, defaultCommand, defaultArgs, workDir);
    }

    /**
     * 创建会话：为指定 groupId 创建并启动一个 AcpClient（自定义参数）。
     *
     * @param groupId       分组标识
     * @param command       可执行命令路径
     * @param args          命令参数
     * @param workspacePath 工作目录
     * @throws IOException 启动失败时抛出
     */
    public void createSession(String groupId, String command, String[] args, String workspacePath) throws IOException {
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

        AcpClient client = new AcpClient(command, args, workspacePath, groupId);
        client.start();
        clients.put(groupId, client);
        logger.info("groupId={} 会话创建成功, sessionId={}", groupId, client.getSessionId());
    }

    /**
     * 发送消息：向指定 groupId 的 AcpClient 发送用户消息，可附带图片。
     *
     * @param groupId        分组标识
     * @param message        用户消息
     * @param imageBase64List 图片 base64 列表，可为 null
     * @throws IllegalStateException 如果 groupId 对应的 client 不存在
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
     * 不影响 session 上下文，后续可继续发送消息。
     *
     * @param groupId 分组标识
     * @throws IOException 发送失败时抛出
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
