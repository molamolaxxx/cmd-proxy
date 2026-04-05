package com.mola.cmd.proxy.app.memory;

import com.mola.cmd.proxy.app.acpclient.ContextMessage;
import com.mola.cmd.proxy.app.acpclient.MemoryManagerBridge;
import com.mola.cmd.proxy.app.memory.model.MemoryConfig;
import com.mola.cmd.proxy.app.memory.model.MemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 记忆系统门面类，主 Client 与记忆模块的唯一交互入口。
 * <p>
 * 实现 {@link MemoryManagerBridge} 接口，通过依赖倒置与 acpclient 包解耦。
 * 对外暴露 3 类操作：读取概要、触发提取、管理记忆。
 */
public class MemoryManager implements MemoryManagerBridge {

    private static final Logger logger = LoggerFactory.getLogger(MemoryManager.class);

    private final MemoryConfig config;
    private final MemoryExtractor extractor;
    private final MemoryLoader loader;
    private final MemoryFileStore fileStore;

    public MemoryManager(MemoryConfig config, String command, String[] args) {
        this.config = config;
        this.fileStore = new MemoryFileStore(config.getBaseDir());
        this.loader = new MemoryLoader(fileStore, config);
        this.extractor = new MemoryExtractor(config, command, args, fileStore);
    }

    // ==================== MemoryManagerBridge 实现 ====================

    @Override
    public String buildMemoryPrompt(String workspacePath) {
        if (!config.isEnabled()) return "";
        try {
            return loader.buildMemoryPrompt(workspacePath);
        } catch (Exception e) {
            logger.error("构建记忆概要失败", e);
            return "";
        }
    }

    @Override
    public void submitExtract(String workspacePath, List<ContextMessage> history) {
        if (!config.isEnabled() || history == null || history.isEmpty()) return;
        extractor.submitExtract(workspacePath, history);
    }

    @Override
    public void submitExtractFull(String workspacePath, List<ContextMessage> history) {
        if (!config.isEnabled() || history == null || history.isEmpty()) return;
        extractor.submitExtractFull(workspacePath, history);
    }

    // ==================== 记忆管理 ====================

    /**
     * 删除指定记忆。
     *
     * @param workspacePath 当前工作目录
     * @param memoryId      记忆 ID
     * @return true 如果成功删除
     */
    public boolean deleteMemory(String workspacePath, String memoryId) {
        return fileStore.deleteMemory(workspacePath, memoryId);
    }

    /**
     * 列出项目的所有记忆。
     *
     * @param workspacePath 当前工作目录
     * @return 记忆列表
     */
    public List<MemoryEntry> listMemories(String workspacePath) {
        return fileStore.listMemories(workspacePath);
    }

    /**
     * 清理过期的 project 类型记忆。
     *
     * @param workspacePath 当前工作目录
     * @return 清理的记忆数量
     */
    public int cleanExpiredMemories(String workspacePath) {
        return fileStore.cleanExpiredMemories(workspacePath, config.getProjectExpireDays());
    }

    /**
     * 获取记忆系统配置。
     */
    public MemoryConfig getConfig() {
        return config;
    }

    /**
     * 关闭记忆系统，释放资源。
     */
    public void shutdown() {
        extractor.shutdown();
    }
}
