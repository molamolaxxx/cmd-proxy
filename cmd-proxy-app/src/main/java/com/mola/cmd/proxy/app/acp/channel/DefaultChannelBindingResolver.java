package com.mola.cmd.proxy.app.acp.channel;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelBinding;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import com.mola.cmd.proxy.app.acp.team.TeamManager;
import com.mola.cmd.proxy.app.acp.team.talkto.TeamTalkToDispatcher;

/** Local-instance binding resolver. Cross-instance Team placement is deliberately unsupported. */
public final class DefaultChannelBindingResolver implements ChannelBindingResolver {
    private final AcpClientRegistry mainRegistry;
    private final TalkToDispatcher mainDispatcher;
    private final TeamManager teamManager;

    public DefaultChannelBindingResolver(AcpClientRegistry mainRegistry,
                                         TalkToDispatcher mainDispatcher,
                                         TeamManager teamManager) {
        this.mainRegistry = mainRegistry;
        this.mainDispatcher = mainDispatcher;
        this.teamManager = teamManager;
    }

    @Override
    public ChannelBoundTarget resolve(ChannelBinding binding) {
        if (binding == null) return null;
        if (ChannelBinding.TYPE_MAIN.equals(binding.getType())) {
            String groupId = trim(binding.getGroupId());
            AcpClient client = mainRegistry.getClient(groupId);
            return client == null ? null
                    : new ChannelBoundTarget(client, mainDispatcher, groupId, groupId);
        }
        if (ChannelBinding.TYPE_TEAM_MEMBER.equals(binding.getType()) && teamManager != null) {
            String teamId = trim(binding.getTeamId());
            String memberId = trim(binding.getTeamMemberId());
            AcpClient client = teamManager.getClientRegistry().get(teamId, memberId).orElse(null);
            if (client == null) return null;
            TeamTalkToDispatcher dispatcher = teamManager.getOrCreateTalkToDispatcher(teamId);
            return new ChannelBoundTarget(client, dispatcher, memberId,
                    "team:" + teamId + ":" + memberId);
        }
        return null;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
