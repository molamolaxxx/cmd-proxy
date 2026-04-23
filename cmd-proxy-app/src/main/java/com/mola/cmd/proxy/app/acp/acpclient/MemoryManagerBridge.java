package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage;

import java.util.List;

/**
 * 记忆管理器桥接接口，定义在 acpclient 包中，由 memory 包实现。
 * 目的：避免 acpclient 包直接依赖 memory 包，实现依赖倒置。
 */
public interface MemoryManagerBridge {

    /**
     * 构建记忆概要文本，注入到 prompt 前面。
     */
    String buildMemoryPrompt(String workspacePath);

    /**
     * 提交增量记忆提取任务到异步队列。
     * 只分析上次提取之后的新对话。每 N 轮触发时使用。
     */
    void submitExtract(String workspacePath, List<ContextMessage> history);

    /**
     * 提交全量记忆提取任务到异步队列。
     * 分析完整对话历史。session 结束时使用，确保不遗漏。
     */
    void submitExtractFull(String workspacePath, List<ContextMessage> history);

    /**
     * 递增 session 计数，用于 Dream（记忆整理）触发条件判断。
     * 在 AcpClient.close() 中调用。
     */
    void incrementSessionCount(String workspacePath);

    /**
     * 记录一次记忆访问（访问强化）。
     * 当 Agent 读取了 memories/ 目录下的明细文件时调用。
     *
     * @param workspacePath 当前工作目录
     * @param filePath      被读取的文件路径
     */
    void onMemoryAccessed(String workspacePath, String filePath);
}
