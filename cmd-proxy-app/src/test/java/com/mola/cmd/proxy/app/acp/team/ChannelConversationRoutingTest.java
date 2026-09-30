package com.mola.cmd.proxy.app.acp.team;

import com.alibaba.fastjson.JSON;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry;
import com.mola.cmd.proxy.app.acp.channel.*;
import com.mola.cmd.proxy.app.acp.channel.model.*;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import com.mola.cmd.proxy.app.acp.team.model.*;
import org.junit.Test;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class ChannelConversationRoutingTest {
    @Test
    public void routesGroupAndSingleExplicitlyWithoutFallbackAndSurvivesSerialization() throws Exception {
        TeamClientRegistry registry = new TeamClientRegistry();
        TeamManager manager = new TeamManager(new TeamStore(Files.createTempDirectory("mapping-team")), registry);
        TeamDefinition team = TeamDefinition.creating("team-1", "owner", "Team", "instance", "request",
                Arrays.asList(member("member-a"), member("member-b")), 100L)
                .transitionTo(TeamState.READY, null, 101L);
        manager.attachPersistedDefinition(team);
        AcpClient a = client("member-a"), b = client("member-b");
        registry.register("team-1", "member-a", a);
        registry.register("team-1", "member-b", b);
        ChannelBinding binding = binding();
        binding.setConversationMappings(Arrays.asList(mapping("group", "chat-1", "member-a"),
                mapping("group", "chat-2", "member-a"), mapping("single", "user-1", "member-b")));
        binding = JSON.parseObject(JSON.toJSONString(binding), ChannelBinding.class);
        DefaultChannelBindingResolver resolver = new DefaultChannelBindingResolver(AcpClientRegistry.getInstance(), null, manager);
        try {
            assertSame(a, resolver.resolve(binding, event("group", "chat-1", "user-1")).getClient());
            assertSame(a, resolver.resolve(binding, event("group", "chat-1", "other-sender")).getClient());
            assertSame(a, resolver.resolve(binding, event("group", "chat-2", "user-1")).getClient());
            assertSame(b, resolver.resolve(binding, event("single", "ignored-chat", "user-1")).getClient());
            assertNull(resolver.resolve(binding, event("group", "unknown", "user-1")));
            assertNull(resolver.resolve(binding));
            binding.getConversationMappings().get(0).setTeamMemberId("removed-member");
            assertNull(resolver.resolve(binding, event("group", "chat-1", "user-1")));
            manager.getRuntime("team-1").get().stopAcceptingRequests();
            assertNull(resolver.resolve(binding, event("single", "ignored-chat", "user-1")));
        } finally { manager.close(); }
    }

    @Test
    public void validatesDuplicatesMembershipAndAllowsManyConversationsPerMember() {
        ChannelBinding binding = binding();
        binding.setConversationMappings(Arrays.asList(mapping("group", "same-id", "member-a"),
                mapping("single", "same-id", "member-a")));
        assertNull(binding.validateConversationMappings(Collections.singleton("member-a")));
        assertNotNull(binding.validateConversationMappings(Collections.singleton("member-b")));
        binding.getConversationMappings().get(1).setChatType("group");
        assertEquals("duplicate conversation mapping", binding.validateConversationMappings(null));
        binding.setConversationMappings(Collections.emptyList());
        assertNull(binding.validateConversationMappings(Collections.emptySet()));
        assertEquals("FIXED", new ChannelBinding().effectiveTeamMemberSelection());
    }

    public static ChannelBinding binding() {
        ChannelBinding binding = new ChannelBinding();
        binding.setType(ChannelBinding.TYPE_TEAM_MEMBER);
        binding.setInstanceId("instance");
        binding.setTeamId("team-1");
        binding.setTeamMemberSelection(ChannelBinding.MEMBER_SELECTION_CONVERSATION_MAPPING);
        return binding;
    }
    public static ChannelConversationMapping mapping(String type, String id, String member) {
        ChannelConversationMapping mapping = new ChannelConversationMapping();
        mapping.setChatType(type); mapping.setConversationId(id); mapping.setTeamMemberId(member);
        return mapping;
    }
    private static TeamMemberDefinition member(String id) {
        return new TeamMemberDefinition(id, "source-" + id, id, id, "remark", "fingerprint");
    }
    private static AcpClient client(String member) throws Exception {
        AcpRobotParam robot = new AcpRobotParam(); robot.setName(member);
        return new AcpClient(Files.createTempDirectory("mapped-client").toString(),
                AcpClientIdentity.team("client-" + member, "instance", "team/team-1/" + member,
                        "owner", "team-1", member, member), robot);
    }
    private static ChannelEvent event(String type, String chat, String sender) {
        return new ChannelEvent("channel", "msg", sender, sender, "hello",
                new ChannelReplyRoute("request", "msg", sender, chat, type, System.currentTimeMillis() + 60000));
    }
}
