package com.mola.cmd.proxy.app.acp.talkto.model;

import com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** A FIFO batch of compatible internal TalkTo messages delivered in one ACP turn. */
public final class TalkToBatchMessage extends TalkToMessage {

    private final List<TalkToMessage> messages;

    public TalkToBatchMessage(List<TalkToMessage> messages) {
        super(first(messages).getSender(), combinedContent(messages),
                maxDepth(messages), combinedAttachments(messages),
                first(messages).getAuthPrincipalContext(), deepestTrace(messages));
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
    }

    public List<TalkToMessage> getMessages() {
        return messages;
    }

    @Override
    public String buildPrompt() {
        StringBuilder result = new StringBuilder();
        result.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        result.append("📬 [ACP 批量路由消息] 发送者: ").append(getSender()).append("\n");
        result.append("共 ").append(messages.size()).append(" 条，已合并为一个 turn\n");
        result.append("━━━━━━━━━━━━━━━━━━\n\n");
        for (int i = 0; i < messages.size(); i++) {
            result.append("[").append(i + 1).append("] ")
                    .append(messages.get(i).getContent()).append("\n\n");
        }
        appendReplyPolicy(result, getSender(), getDepth());
        return result.toString();
    }

    public static boolean compatible(TalkToMessage first, TalkToMessage candidate) {
        if (first == null || candidate == null
                || first instanceof TalkToBatchMessage
                || candidate instanceof TalkToBatchMessage) return false;
        if (!first.isBatchable() || !candidate.isBatchable()) return false;
        if (!safeEquals(first.getSender(), candidate.getSender())) return false;
        try {
            TalkToTrace.merge(java.util.Arrays.asList(first.getTrace(), candidate.getTrace()));
        } catch (IllegalArgumentException tooMany) { return false; }
        return samePrincipal(first.getAuthPrincipalContext(),
                candidate.getAuthPrincipalContext());
    }

    public static boolean canAppend(List<TalkToMessage> batch, TalkToMessage candidate) {
        if (!compatible(batch.get(0), candidate)) return false;
        List<TalkToTrace> traces = new ArrayList<>();
        for (TalkToMessage message : batch) traces.add(message.getTrace());
        traces.add(candidate.getTrace());
        try { TalkToTrace.merge(traces); return true; }
        catch (IllegalArgumentException tooMany) { return false; }
    }

    private static boolean samePrincipal(AuthPrincipalContext left,
                                         AuthPrincipalContext right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return safeEquals(left.getPrincipalId(), right.getPrincipalId())
                && safeEquals(left.getSourceType(), right.getSourceType())
                && safeEquals(left.getSourceId(), right.getSourceId());
    }

    private static boolean safeEquals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static TalkToMessage first(List<TalkToMessage> messages) {
        if (messages == null || messages.size() < 2) {
            throw new IllegalArgumentException("TalkTo batch requires at least two messages");
        }
        for (TalkToMessage message : messages) {
            if (message == null) throw new IllegalArgumentException("TalkTo batch contains null");
        }
        return messages.get(0);
    }

    private static int maxDepth(List<TalkToMessage> messages) {
        first(messages);
        int depth = 0;
        for (TalkToMessage message : messages) depth = Math.max(depth, message.getDepth());
        return depth;
    }

    private static TalkToTrace deepestTrace(List<TalkToMessage> messages) {
        TalkToMessage first = first(messages);
        java.util.List<TalkToTrace> traces = new ArrayList<>();
        for (TalkToMessage message : messages) {
            if (message != first && !compatible(first, message)) {
                throw new IllegalArgumentException("Incompatible TalkTo batch");
            }
            traces.add(message.getTrace());
        }
        return TalkToTrace.merge(traces);
    }

    private static String combinedContent(List<TalkToMessage> messages) {
        first(messages);
        StringBuilder result = new StringBuilder();
        for (TalkToMessage message : messages) {
            if (result.length() > 0) result.append("\n\n");
            result.append(message.getContent());
        }
        return result.toString();
    }

    private static List<String> combinedAttachments(List<TalkToMessage> messages) {
        first(messages);
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (TalkToMessage message : messages) paths.addAll(message.getLocalAttachments());
        return new ArrayList<>(paths);
    }
}
