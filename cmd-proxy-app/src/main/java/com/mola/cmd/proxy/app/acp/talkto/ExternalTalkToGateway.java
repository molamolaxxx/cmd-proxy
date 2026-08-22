package com.mola.cmd.proxy.app.acp.talkto;

import com.mola.cmd.proxy.app.acp.channel.model.ChannelDeliveryContext;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelTurnContext;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToRequest;

/** A non-robot TalkTo destination such as a short-lived external channel route. */
public interface ExternalTalkToGateway {
    boolean supports(String target);

    String deliver(TalkToRequest request, String senderName, String senderGroupId);

    /** Releases a short-lived target after its owning logical turn finishes. */
    default void release(String target) { }

    /** Restores a new turn-scoped target from a durable scheduled delivery address. */
    default ChannelTurnContext restoreChannelTurn(ChannelDeliveryContext context,
                                                  String senderGroupId) {
        return null;
    }
}
