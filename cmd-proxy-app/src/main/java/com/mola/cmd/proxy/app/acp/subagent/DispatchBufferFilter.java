package com.mola.cmd.proxy.app.acp.subagent;

import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatch 指令缓冲过滤器。
 * <p>
 * 作为 agent_message_chunk 和 listener.onMessage() 之间的中间层，
 * 拦截可能是 dispatch_subagent JSON 的文本片段，避免推送给用户。
 * <p>
 * 状态机：
 * <pre>
 * NORMAL → 检测到 dispatch 前缀 → BUFFERING → JSON 完整且是 dispatch → CAPTURED
 *                                            → 确认不是 dispatch    → flush 缓冲区，回到 NORMAL
 * </pre>
 * <p>
 * 使用方式：每个 sendPrompt 调用创建一个实例，在 agent_message_chunk 中
 * 调用 {@link #accept(String)} 代替直接调用 listener.onMessage()。
 */
public class DispatchBufferFilter {

    private static final Logger logger = LoggerFactory.getLogger(DispatchBufferFilter.class);

    /** dispatch JSON 的特征前缀 */
    private static final String DISPATCH_PREFIX = "{\"action\":\"dispatch_subagent\"";
    /** 前缀的宽松匹配（LLM 可能在 { 前有换行或空格） */
    private static final String DISPATCH_TRIGGER = "dispatch_subagent";

    private enum State { NORMAL, BUFFERING }

    private final AcpResponseListener listener;
    private final boolean enabled;

    private State state = State.NORMAL;
    private final StringBuilder buffer = new StringBuilder();
    /** 标记是否成功捕获了 dispatch JSON（turn 结束后由 AcpClient 检查） */
    private boolean captured = false;

    /**
     * @param listener 下游 listener
     * @param enabled  是否启用过滤（未配置 subAgent 时传 false，直通模式）
     */
    public DispatchBufferFilter(AcpResponseListener listener, boolean enabled) {
        this.listener = listener;
        this.enabled = enabled;
    }

    /**
     * 接收一个 agent_message_chunk 文本片段。
     * 根据状态机决定是直接推送、缓冲还是吞掉。
     *
     * @param text 文本片段
     */
    public void accept(String text) {
        if (!enabled) {
            listener.onMessage(text);
            return;
        }

        switch (state) {
            case NORMAL:
                handleNormal(text);
                break;
            case BUFFERING:
                handleBuffering(text);
                break;
        }
    }

    private void handleNormal(String text) {
        // 把 text 追加到一个临时视角，看看累积内容是否开始像 dispatch JSON
        // 关键：LLM 输出 dispatch JSON 时，通常以 { 开头或前面只有换行
        // 我们检查当前 chunk 是否包含 { 且后续可能是 dispatch

        int braceIdx = text.indexOf('{');
        if (braceIdx < 0) {
            // 没有 {，正常推送
            listener.onMessage(text);
            return;
        }

        // { 前面的部分正常推送
        if (braceIdx > 0) {
            listener.onMessage(text.substring(0, braceIdx));
        }

        // 从 { 开始进入缓冲
        String fromBrace = text.substring(braceIdx);
        buffer.setLength(0);
        buffer.append(fromBrace);

        // 检查缓冲区是否已经能判断
        if (checkBuffer()) return;

        // 还不能判断，进入缓冲模式
        state = State.BUFFERING;
    }

    private void handleBuffering(String text) {
        buffer.append(text);
        checkBuffer();
    }

    /**
     * 检查缓冲区内容，决定下一步动作。
     *
     * @return true 如果已做出最终判断（flush 或 capture）
     */
    private boolean checkBuffer() {
        String content = buffer.toString();

        // 快速判断：如果缓冲区已经足够长但不包含 dispatch 关键词，不是 dispatch
        if (content.length() > DISPATCH_PREFIX.length()
                && !content.contains(DISPATCH_TRIGGER)) {
            flushBuffer();
            return true;
        }

        // 如果缓冲区还很短，可能是 dispatch 前缀的一部分，继续等
        if (content.length() < DISPATCH_PREFIX.length()) {
            // 但如果已经能确认不是 JSON 开头，立即 flush
            String trimmed = content.trim();
            if (!trimmed.isEmpty() && !DISPATCH_PREFIX.startsWith(trimmed)
                    && !trimmed.startsWith("{")) {
                flushBuffer();
                return true;
            }
            return false;
        }

        // 缓冲区足够长，检查是否是完整的 dispatch JSON
        if (content.contains(DISPATCH_TRIGGER)) {
            // 检查 JSON 是否闭合（简单的花括号计数）
            if (isJsonComplete(content)) {
                // 确认是 dispatch JSON，吞掉不推送
                logger.info("缓冲区捕获 dispatch JSON，长度={}", content.length());
                captured = true;
                state = State.NORMAL;
                buffer.setLength(0);
                return true;
            }
            // JSON 还没闭合，继续缓冲
            return false;
        }

        // 不是 dispatch，flush
        flushBuffer();
        return true;
    }

    /**
     * 将缓冲区内容推送给用户，回到 NORMAL 状态。
     */
    private void flushBuffer() {
        if (buffer.length() > 0) {
            listener.onMessage(buffer.toString());
            buffer.setLength(0);
        }
        state = State.NORMAL;
    }

    /**
     * 简单的 JSON 闭合检测：计数花括号和方括号。
     */
    private static boolean isJsonComplete(String text) {
        int braces = 0;
        int brackets = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (c == '{') braces++;
            else if (c == '}') braces--;
            else if (c == '[') brackets++;
            else if (c == ']') brackets--;
        }
        return braces == 0 && brackets == 0 && text.contains("}");
    }

    /**
     * turn 结束时调用。如果还有未 flush 的缓冲区内容，推送给用户。
     */
    public void flush() {
        if (buffer.length() > 0 && !captured) {
            flushBuffer();
        }
        buffer.setLength(0);
        state = State.NORMAL;
    }

    /**
     * 是否成功捕获了 dispatch JSON（即 JSON 被吞掉没推给用户）。
     */
    public boolean isCaptured() {
        return captured;
    }
}
