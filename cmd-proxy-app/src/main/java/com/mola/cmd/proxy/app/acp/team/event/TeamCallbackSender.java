package com.mola.cmd.proxy.app.acp.team.event;

import com.mola.cmd.proxy.client.resp.CmdResponseContent;

@FunctionalInterface
public interface TeamCallbackSender {

    void send(String command, String group, CmdResponseContent response);
}
