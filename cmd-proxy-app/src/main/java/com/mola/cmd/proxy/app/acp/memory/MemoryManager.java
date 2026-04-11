package com.mola.cmd.proxy.app.acp.memory;

import com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage;
import com.mola.cmd.proxy.app.acp.acpclient.MemoryManagerBridge;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryEntry;
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
    private final MemoryDreamer dreamer;

    public MemoryManager(MemoryConfig config) {
        this.config = config;
        this.fileStore = new MemoryFileStore(config.getBaseDir());
        this.loader = new MemoryLoader(fileStore, config);
        this.extractor = new MemoryExtractor(config, fileStore);
        this.dreamer = new MemoryDreamer(config, fileStore);
    }

    // ==================== MemoryManagerBridge 实现 ====================

    @Override
    public String buildMemoryPrompt(String workspacePath) {
        if (!config.isEnabled()) return "";
        try {
            // 检查是否需要自动整理
            checkAndTriggerDream(workspacePath);
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

    @Override
    public void incrementSessionCount(String workspacePath) {
        if (!config.isEnabled()) return;
        try {
            fileStore.incrementDreamSessionCount(workspacePath);
        } catch (Exception e) {
            logger.warn("递增 session 计数失败", e);
        }
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
     * 获取上次 auto dream 的时间。
     * 供能力反思服务判断记忆是否发生过整理。
     *
     * @return ISO 格式时间字符串，从未整理过时返回 null
     */
    public String getLastDreamTime(String workspacePath) {
        return fileStore.loadDreamState(workspacePath).getLastDreamTime();
    }

    /**
     * 构建纯记忆条目概要，不含主 agent 专用的警告和操作提示。
     * 供能力反思等子系统使用。
     */
    public String buildMemorySummary(String workspacePath) {
        if (!config.isEnabled()) return "";
        try {
            return loader.buildMemorySummary(workspacePath);
        } catch (Exception e) {
            logger.error("构建记忆概要失败", e);
            return "";
        }
    }

    /**
     * 手动触发记忆整理（供 acpMemoryDream 命令调用）。
     */
    public void triggerDream(String workspacePath) {
        if (!config.isEnabled()) return;
        dreamer.submitDream(workspacePath);
    }

    /**
     * 检查并触发自动整理。
     */
    private void checkAndTriggerDream(String workspacePath) {
        try {
            if (dreamer.shouldDream(workspacePath)) {
                logger.info("满足自动整理条件，触发 Memory Dream, workspacePath={}", workspacePath);
                dreamer.submitDream(workspacePath);
            }
        } catch (Exception e) {
            logger.warn("检查整理条件失败", e);
        }
    }

    /**
     * 关闭记忆系统，释放资源。
     */
    public void shutdown() {
        extractor.shutdown();
        dreamer.shutdown();
    }
}
