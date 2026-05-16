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

    public TalkToRequest(String target, String content, int depth) {
        this.target = target;
        this.content = content;
        this.depth = depth;
    }

    public String getTarget() { return target; }
    public String getContent() { return content; }
    public int getDepth() { return depth; }
}
