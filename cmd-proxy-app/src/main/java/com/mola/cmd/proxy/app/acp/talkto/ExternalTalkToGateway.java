package com.mola.cmd.proxy.app.acp.talkto;

import com.mola.cmd.proxy.app.acp.talkto.model.TalkToRequest;

/** A non-robot TalkTo destination such as a short-lived external channel route. */
public interface ExternalTalkToGateway {
    boolean supports(String target);

    String deliver(TalkToRequest request, String senderName, String senderGroupId);
}
