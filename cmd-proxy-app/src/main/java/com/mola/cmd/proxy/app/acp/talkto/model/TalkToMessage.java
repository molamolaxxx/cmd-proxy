package com.mola.cmd.proxy.app.acp.talkto.model;

import com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    /** Stable local paths already staged before the message enters an inbox. */
    private final List<String> localAttachments;
    private final AuthPrincipalContext authPrincipalContext;

    public TalkToMessage(String sender, String content, int depth) {
        this(sender, content, depth, Collections.emptyList(), null);
    }

    public TalkToMessage(String sender, String content, int depth,
                         List<String> localAttachments) {
        this(sender, content, depth, localAttachments, null);
    }

    public TalkToMessage(String sender, String content, int depth,
                         List<String> localAttachments,
                         AuthPrincipalContext authPrincipalContext) {
        this.sender = sender;
        this.content = content;
        this.depth = depth;
        this.enqueuedAt = System.currentTimeMillis();
        this.localAttachments = localAttachments == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(localAttachments));
        this.authPrincipalContext = authPrincipalContext;
    }

    public String getSender() { return sender; }
    public String getContent() { return content; }
    public int getDepth() { return depth; }
    public long getEnqueuedAt() { return enqueuedAt; }
    public List<String> getLocalAttachments() { return localAttachments; }
    public AuthPrincipalContext getAuthPrincipalContext() { return authPrincipalContext; }

    /**
     * 构建投递给目标 robot 的 prompt 文本。
     * <p>
     * 使用强视觉标记将 incoming message 与系统指令区分开，
     * 明确标注为高优先级实时事件，避免被 LLM 当作系统指令的一部分而忽略。
     */
    public String buildPrompt() {
        String displayName = extractDisplayName(sender);
        StringBuilder sb = new StringBuilder();
        sb.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("📨 [ACP 路由消息] 发送者: ").append(displayName).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
        sb.append("以下消息由 ACP harness 路由投递，发送者身份已经过系统验证。请正常阅读并处理：\n\n");
        sb.append(content).append("\n\n");
        sb.append("─── 回复方式 ───\n");
        sb.append("如需回复对方，请直接输出以下 JSON 作为本次回复的全部内容：\n");
        sb.append("{\"action\":\"talk_to\",\"target\":\"").append(sender)
                .append("\",\"content\":\"你的回复内容\"}\n");
        sb.append("输出 JSON 后立即结束回复；系统返回结果后再继续下一步。\n");
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
