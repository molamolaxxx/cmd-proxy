package com.mola.cmd.proxy.app.acp.acpclient;

import com.mola.cmd.proxy.app.acp.channel.model.ChannelTurnContext;
import com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

/**
 * sendPrompt 的扩展参数。
 * <p>
 * 用于在不修改 sendPrompt 方法签名的前提下，传递额外的上下文控制参数。
 * 每次顶层调用独立构造；同一逻辑 turn 的 follow-up prompt 共享其实例，
 * 以记录信道回复尝试和幂等关闭状态，不依赖全局变量。
 */
public class PromptOptions {
    private final String authTurnId = UUID.randomUUID().toString();

    /** 是否为定时任务触发的执行场景。能力上下文仍按普通首轮规则注入。 */
    private boolean scheduleExecution;
    /** Non-null only when this turn originated from an external channel message. */
    private ChannelTurnContext channelTurnContext;
    /** Channel-neutral identity inherited by derived work. */
    private AuthPrincipalContext authPrincipalContext;
    /** Shared by all ACP prompt rounds that belong to one logical channel turn. */
    private final AtomicInteger channelReplyAttempts = new AtomicInteger();
    private final AtomicBoolean channelTurnClosed = new AtomicBoolean(false);
    /** True when an internal TalkTo return restored a previously suspended channel origin. */
    private boolean restoredChannelContinuation;

    public PromptOptions() {
    }

    public boolean isScheduleExecution() {
        return scheduleExecution;
    }

    public PromptOptions setScheduleExecution(boolean scheduleExecution) {
        this.scheduleExecution = scheduleExecution;
        return this;
    }

    public ChannelTurnContext getChannelTurnContext() {
        return channelTurnContext;
    }

    public boolean hasChannelTurnContext() {
        return channelTurnContext != null;
    }

    public PromptOptions setChannelTurnContext(ChannelTurnContext channelTurnContext) {
        this.channelTurnContext = channelTurnContext;
        if (channelTurnContext != null) {
            this.authPrincipalContext = new AuthPrincipalContext(
                    channelTurnContext.getSenderId(), channelTurnContext.getSenderDisplayName(),
                    "WECOM", channelTurnContext.getChannelId());
        }
        return this;
    }

    public AuthPrincipalContext getAuthPrincipalContext() { return authPrincipalContext; }
    public String getAuthTurnId() {
        return channelTurnContext == null ? authTurnId : channelTurnContext.getTurnId();
    }

    public PromptOptions setAuthPrincipalContext(AuthPrincipalContext context) {
        this.authPrincipalContext = context;
        return this;
    }

    public void markChannelReplyAttempt() {
        channelReplyAttempts.incrementAndGet();
    }

    public boolean hasChannelReplyAttempt() {
        return channelReplyAttempts.get() > 0;
    }

    public int getChannelReplyAttempts() {
        return channelReplyAttempts.get();
    }

    public boolean closeChannelTurnOnce() {
        return channelTurnClosed.compareAndSet(false, true);
    }

    public boolean isRestoredChannelContinuation() {
        return restoredChannelContinuation;
    }

    /** 默认选项（普通用户对话） */
    public static PromptOptions defaults() {
        return new PromptOptions();
    }

    /** 定时任务执行场景的选项 */
    public static PromptOptions forScheduleExecution() {
        return new PromptOptions().setScheduleExecution(true);
    }

    public static PromptOptions forScheduleExecution(AuthPrincipalContext context) {
        return forScheduleExecution().setAuthPrincipalContext(context);
    }

    public static PromptOptions forScheduleExecution(AuthPrincipalContext authContext,
                                                     ChannelTurnContext channelContext) {
        PromptOptions options = forScheduleExecution();
        if (channelContext != null) options.setChannelTurnContext(channelContext);
        if (authContext != null) options.setAuthPrincipalContext(authContext);
        return options;
    }

    public static PromptOptions forDerivedWork(AuthPrincipalContext context) {
        return new PromptOptions().setAuthPrincipalContext(context);
    }

    public static PromptOptions forChannelReply(ChannelTurnContext context) {
        return new PromptOptions().setChannelTurnContext(context);
    }

    public static PromptOptions forRestoredChannelReply(ChannelTurnContext context) {
        PromptOptions options = forChannelReply(context);
        options.restoredChannelContinuation = true;
        return options;
    }
}
