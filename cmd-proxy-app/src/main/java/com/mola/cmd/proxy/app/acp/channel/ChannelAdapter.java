package com.mola.cmd.proxy.app.acp.channel;

import com.mola.cmd.proxy.app.acp.channel.model.ChannelReplyRoute;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelSendResult;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelStatus;

public interface ChannelAdapter {
    String getChannelId();
    ChannelStatus getStatus();
    void start();
    void stop();
    ChannelSendResult send(ChannelReplyRoute route, String markdown);
    ChannelSendResult sendProactive(String chatId, String markdown);
}
