package com.mola.cmd.proxy.app.acp.talkto.model;

/**
 * inbox 中排队的 talkTo 消息。
 */
public class TalkToMessage {

    /** 发送方 robot 名称 */
    private final String sender;

    /** 消息内容 */
    private final String content;

    /** 消息深度（防循环） */
    private final int depth;

    /** 入队时间戳 */
    private final long enqueuedAt;

    public TalkToMessage(String sender, String content, int depth) {
        this.sender = sender;
        this.content = content;
        this.depth = depth;
        this.enqueuedAt = System.currentTimeMillis();
    }

    public String getSender() { return sender; }
    public String getContent() { return content; }
    public int getDepth() { return depth; }
    public long getEnqueuedAt() { return enqueuedAt; }

    /**
     * 构建投递给目标 robot 的 prompt 文本。
     */
    public String buildPrompt() {
        String displayName = extractDisplayName(sender);
        StringBuilder sb = new StringBuilder();
        sb.append("[Incoming Message]\n");
        sb.append("来自: ").append(displayName).append("\n");
        sb.append("内容: ").append(content).append("\n\n");
        sb.append("如果你完成任务后需要回复对方，在回复中输出以下 JSON：\n");
        sb.append("{\"action\":\"talk_to\",\"target\":\"").append(sender)
                .append("\",\"content\":\"你的回复内容\"}\n");
        sb.append("如果不需要回复，正常完成任务即可。\n");
        return sb.toString();
    }

    /**
     * 从 sender 中提取显示名称。
     * 如果 sender 包含 ":"（跨 chatter 格式 "chatterId:robotName"），只取 robotName 部分展示。
     */
    private static String extractDisplayName(String sender) {
        if (sender != null && sender.contains(":")) {
            return sender.substring(sender.indexOf(':') + 1);
        }
        return sender;
    }
}
