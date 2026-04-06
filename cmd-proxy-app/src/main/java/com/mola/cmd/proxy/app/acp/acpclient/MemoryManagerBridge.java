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
}
