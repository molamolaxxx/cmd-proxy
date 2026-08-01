package com.mola.cmd.proxy.app.acp.acpclient.listener;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.client.provider.CmdReceiver;
import com.mola.cmd.proxy.client.resp.CmdResponseContent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AcpResponseListener 的默认实现，将 agent 输出通过 sendContent 回调。
 */
public class DefaultAcpResponseListener implements AcpResponseListener {

    private final String groupId;
    private final AcpResponseContentRenderer renderer;
    private final AcpResponseContentRenderer.Output output;

    /** 缓冲模式：开启后 sendContent 不立即发送，而是攒到 buffer 中 */
    private boolean buffering = false;
    private final StringBuilder buffer = new StringBuilder();

    public DefaultAcpResponseListener(String groupId) {
        this(groupId, null);
    }

    DefaultAcpResponseListener(
            String groupId, AcpResponseContentRenderer.Output output) {
        this.groupId = groupId;
        this.output = output == null ? this::doCallback : output;
        this.renderer = new AcpResponseContentRenderer(this::sendContent);
    }

    /**
     * 开启缓冲模式，后续 sendContent 调用会攒到内部 buffer 中，不立即发送。
     */
    public void beginBuffer() {
        buffering = true;
        buffer.setLength(0);
    }

    /**
     * 结束缓冲模式，将 buffer 中积攒的内容一次性发送给客户端。
     */
    public void flushBuffer() {
        buffering = false;
        if (buffer.length() > 0) {
            doSend(buffer.toString(), false);
            buffer.setLength(0);
        }
    }

    @Override
    public void onMessage(String text) {
        renderer.onMessage(text);
    }

    @Override
    public void onToolCall(String toolCallId, String title, String status, JsonObject update) {
        renderer.onToolCall(title, status, update);
    }

    @Override
    public void onComplete(String fullResponse) {
        renderer.onComplete();
    }

    @Override
    public void onError(Exception error) {
        renderer.onError(error);
    }

    @Override
    public void onSubAgentEvent(String eventType, String agentName, String detail) {
        renderer.onSubAgentEvent(eventType, agentName, detail);
    }

    @Override
    public void onScheduleEvent(String eventType, String detail, boolean expanded) {
        renderer.onScheduleEvent(eventType, detail, expanded);
    }

    @Override
    public void onTalkToEvent(String eventType, String robotName, String messageContent) {
        renderer.onTalkToEvent(eventType, robotName, messageContent);
    }

    @Override
    public void onCompactionEvent(String eventType, String provider) {
        renderer.onCompactionEvent(eventType, provider);
    }

    private void sendContent(String content, boolean end) {
        if (buffering && !end) {
            buffer.append(content);
            return;
        }
        // end=true 时强制 flush buffer 再发送终止帧
        if (buffering && end) {
            buffer.append(content);
            buffering = false;
            doSend(buffer.toString(), true);
            buffer.setLength(0);
            return;
        }
        doSend(content, end);
    }

    private void doSend(String content, boolean end) {
        output.emit(content, end);
    }

    private void doCallback(String content, boolean end) {
        Map<String, String> resultMap = new HashMap<>();
        resultMap.put("groupId", groupId);
        resultMap.put("content", content);
        resultMap.put("end", end ? "Y" : "N");
        CmdReceiver.INSTANCE.callback("acp", "acp",
                new CmdResponseContent(UUID.randomUUID().toString(), resultMap));
    }
}
