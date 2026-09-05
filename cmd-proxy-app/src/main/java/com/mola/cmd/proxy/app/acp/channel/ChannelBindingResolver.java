package com.mola.cmd.proxy.app.acp.channel;

import com.mola.cmd.proxy.app.acp.channel.model.ChannelBinding;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelEvent;

/** Resolves dynamic MAIN or TEAM_MEMBER bindings for each inbound event. */
public interface ChannelBindingResolver {
    ChannelBoundTarget resolve(ChannelBinding binding);

    default ChannelBoundTarget resolve(ChannelBinding binding, ChannelEvent event) {
        return resolve(binding);
    }
}
