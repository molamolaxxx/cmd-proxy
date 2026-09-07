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
    private final TalkToTrace trace;

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
        this(sender, content, depth, localAttachments, authPrincipalContext,
                new TalkToTrace(null, null, null, depth, System.currentTimeMillis()));
    }

    public TalkToMessage(String sender, String content, int depth,
                         List<String> localAttachments,
                         AuthPrincipalContext authPrincipalContext,
                         TalkToTrace trace) {
        this.sender = sender;
        this.content = content;
        this.trace = trace == null ? TalkToTrace.root() : trace;
        this.depth = this.trace.getHopCount();
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
    public TalkToTrace getTrace() { return trace; }

    /** External channel turns and other sensitive subclasses may opt out of batching. */
    public boolean isBatchable() { return true; }

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
        appendReplyPolicy(sb, sender, depth);
        return sb.toString();
    }

    protected static void appendReplyPolicy(StringBuilder sb, String sender, int depth) {
        sb.append("─── 回复方式 ───\n");
        sb.append("收到消息不代表必须回复。禁止发送“收到”、“好的”、“谢谢”、")
                .append("“我会处理”等纯确认消息。\n");
        sb.append("只有在产生最终结果、新事实、明确阻塞，或必须回答对方问题时，")
                .append("才调用 talk_to；最终结果默认结束通信链，不要再发确认。\n");
        sb.append("如确需回复，请调用 talk_to MCP 工具，并将 target 精确设置为：")
                .append(sender).append("。\n");
        sb.append("为保留防循环上下文，请将工具参数 _depth 设置为：")
                .append(depth).append("。该值仅用于兼容和诊断，")
                .append("服务端将独立校验通信链。\n");
        sb.append("工具结果会直接返回当前上下文；不要输出 Action JSON。\n");
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
