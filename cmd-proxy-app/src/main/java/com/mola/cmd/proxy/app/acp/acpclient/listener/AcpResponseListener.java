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
