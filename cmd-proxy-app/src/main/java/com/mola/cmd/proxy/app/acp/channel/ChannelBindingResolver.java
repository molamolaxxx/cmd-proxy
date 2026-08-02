package com.mola.cmd.proxy.app.acp.channel;

import com.mola.cmd.proxy.app.acp.channel.model.ChannelBinding;

/** Resolves dynamic MAIN or TEAM_MEMBER bindings for each inbound event. */
public interface ChannelBindingResolver {
    ChannelBoundTarget resolve(ChannelBinding binding);
}
