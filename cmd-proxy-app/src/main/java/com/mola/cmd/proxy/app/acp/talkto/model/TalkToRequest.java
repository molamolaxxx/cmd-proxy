package com.mola.cmd.proxy.app.acp.talkto.model;

/**
 * 从 LLM 输出中解析出的 talk_to 指令。
 */
public class TalkToRequest {

    /** 目标 robot 名称 */
    private final String target;

    /** 消息内容 */
    private final String content;

    /** 消息深度（防循环用） */
    private final int depth;
    /** Causal parent captured from the inbound turn; never supplied authoritatively by the model. */
    private final TalkToTrace parentTrace;

    public TalkToRequest(String target, String content, int depth) {
        this(target, content, depth, null);
    }

    public TalkToRequest(String target, String content, int depth,
                         TalkToTrace parentTrace) {
        this.target = target;
        this.content = content;
        this.depth = depth;
        this.parentTrace = parentTrace;
    }

    public String getTarget() { return target; }
    public String getContent() { return content; }
    public int getDepth() { return depth; }
    public TalkToTrace getParentTrace() { return parentTrace; }

    public TalkToRequest withParentTrace(TalkToTrace trace) {
        return new TalkToRequest(target, content, depth, trace);
    }
}
