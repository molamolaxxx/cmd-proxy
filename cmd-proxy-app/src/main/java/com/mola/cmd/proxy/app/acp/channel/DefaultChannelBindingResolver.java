package com.mola.cmd.proxy.app.acp.channel;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelBinding;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelEvent;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import com.mola.cmd.proxy.app.acp.team.TeamManager;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberState;
import com.mola.cmd.proxy.app.acp.team.model.TeamState;
import com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime;
import com.mola.cmd.proxy.app.acp.team.talkto.TeamTalkToDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Local-instance binding resolver. Cross-instance Team placement is deliberately unsupported. */
public final class DefaultChannelBindingResolver implements ChannelBindingResolver {
    private static final Logger logger = LoggerFactory.getLogger(DefaultChannelBindingResolver.class);
    private final AcpClientRegistry mainRegistry;
    private final TalkToDispatcher mainDispatcher;
    private final TeamManager teamManager;
    private final ChannelAffinityStore affinityStore;

    public DefaultChannelBindingResolver(AcpClientRegistry mainRegistry,
                                         TalkToDispatcher mainDispatcher,
                                         TeamManager teamManager) {
        this.mainRegistry = mainRegistry;
        this.mainDispatcher = mainDispatcher;
        this.teamManager = teamManager;
        this.affinityStore = new ChannelAffinityStore();
    }

    DefaultChannelBindingResolver(AcpClientRegistry mainRegistry,
                                  TalkToDispatcher mainDispatcher,
                                  TeamManager teamManager,
                                  ChannelAffinityStore affinityStore) {
        this.mainRegistry = mainRegistry;
        this.mainDispatcher = mainDispatcher;
        this.teamManager = teamManager;
        this.affinityStore = affinityStore;
    }

    @Override
    public ChannelBoundTarget resolve(ChannelBinding binding) {
        return resolve(binding, null);
    }

    @Override
    public ChannelBoundTarget resolve(ChannelBinding binding, ChannelEvent event) {
        if (binding == null) return null;
        if (ChannelBinding.TYPE_MAIN.equals(binding.getType())) {
            String groupId = trim(binding.getGroupId());
            AcpClient client = mainRegistry.getClient(groupId);
            return client == null ? null
                    : new ChannelBoundTarget(client, mainDispatcher, groupId, groupId);
        }
        if (ChannelBinding.TYPE_TEAM_MEMBER.equals(binding.getType()) && teamManager != null) {
            String teamId = trim(binding.getTeamId());
            String memberId = selectTeamMember(binding, event, teamId);
            if (memberId == null) return null;
            AcpClient client = teamManager.getClientRegistry().get(teamId, memberId).orElse(null);
            if (client == null) return null;
            TeamTalkToDispatcher dispatcher = teamManager.getOrCreateTalkToDispatcher(teamId);
            return new ChannelBoundTarget(client, dispatcher, memberId,
                    "team:" + teamId + ":" + memberId);
        }
        return null;
    }

    private String selectTeamMember(ChannelBinding binding, ChannelEvent event, String teamId) {
        String mode = binding.effectiveTeamMemberSelection();
        if (ChannelBinding.MEMBER_SELECTION_FIXED.equals(mode)) {
            String fixed = trim(binding.getTeamMemberId());
            return fixed.isEmpty() ? null : fixed;
        }
        TeamRuntime runtime = teamManager.getRuntime(teamId).orElse(null);
        if (runtime == null || !runtime.isAcceptingRequests()) return null;
        TeamDefinition team = runtime.getDefinition();
        if (team.getState() != TeamState.READY) return null;
        List<String> stable = new ArrayList<>();
        List<String> selectable = new ArrayList<>();
        for (TeamMemberDefinition member : team.getMembers()) {
            if (member.getState() == TeamMemberState.CLOSING
                    || member.getState() == TeamMemberState.CLOSED) continue;
            String memberId = member.getTeamMemberId();
            stable.add(memberId);
            if (teamManager.getClientRegistry().get(teamId, memberId).isPresent()) {
                selectable.add(memberId);
            }
        }
        if (selectable.isEmpty()) return null;
        if (ChannelBinding.MEMBER_SELECTION_RANDOM.equals(mode)) {
            return selectable.get(ThreadLocalRandom.current().nextInt(selectable.size()));
        }
        if (!ChannelBinding.MEMBER_SELECTION_AFFINITY.equals(mode) || event == null) return null;
        String routingKey = affinityRoutingKey(event);
        if (routingKey == null) return null;
        try {
            ChannelAffinityStore.Selection selection = affinityStore.select(
                    binding.getInstanceId(), event.getChannelId(), teamId,
                    routingKey, stable, selectable);
            if (selection == null) return null;
            logger.info("channel affinity selected: channelId={}, teamId={}, keyDigest={}, "
                            + "teamMemberId={}, reason={}",
                    event.getChannelId(), teamId, selection.getRoutingDigestPrefix(),
                    selection.getTeamMemberId(), selection.getReason());
            return selection.getTeamMemberId();
        } catch (IOException | RuntimeException e) {
            logger.error("channel affinity selection failed: channelId={}, teamId={}",
                    event.getChannelId(), teamId, e);
            return null;
        }
    }

    static String affinityRoutingKey(ChannelEvent event) {
        if (event == null || event.getReplyRoute() == null) return null;
        if ("group".equals(event.getReplyRoute().getChatType())) {
            String chatId = trim(event.getReplyRoute().getChatId());
            return chatId.isEmpty() ? null : "GROUP:" + chatId;
        }
        if ("single".equals(event.getReplyRoute().getChatType())) {
            String userId = trim(event.getSenderId());
            return userId.isEmpty() ? null : "SINGLE:" + userId;
        }
        return null;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
