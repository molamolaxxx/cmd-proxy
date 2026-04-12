package com.mola.cmd.proxy.app.acp.acpclient.listener;


/**
 * ACP 回调监听器，用于接收 agent 的回答输出。
 */
public interface AcpResponseListener {

    /**
     * 收到 agent 文本消息时回调（agent_message_chunk）
     *
     * @param text agent 输出的文本片段
     */
    void onMessage(String text);

    /**
     * 收到工具调用事件时回调（tool_call / tool_call_update）
     *
     * @param toolCallId 工具调用 ID
     * @param title      工具调用标题，如 "Running: pwd"
     * @param status     状态: pending / in_progress / completed / cancelled
     * @param update     完整的 update JSON 对象，可从中提取 kind、rawInput、content 等
     */
    void onToolCall(String toolCallId, String title, String status, com.google.gson.JsonObject update);

    /**
     * 子 Agent 派发事件回调。
     * <p>
     * 与 onMessage 分离，让客户端可以用不同的 UI 展示子 Agent 状态。
     * 默认空实现，向后兼容已有的 Listener 实现。
     *
     * @param eventType  事件类型：DISPATCH_START / AGENT_START / AGENT_COMPLETE / AGENT_ERROR / DISPATCH_COMPLETE
     * @param agentName  子 Agent 名称，DISPATCH_START/DISPATCH_COMPLETE 时为 null
     * @param detail     事件详情（如耗时、错误信息、结果摘要）
     */
    default void onSubAgentEvent(String eventType, String agentName, String detail) {
        // 默认空实现，向后兼容
    }

    /**
     * agent 回答完成时回调
     *
     * @param fullResponse 完整的回答文本
     */
    void onComplete(String fullResponse);

    /**
     * 发生错误时回调
     *
     * @param error 异常信息
     */
    void onError(Exception error);
}
