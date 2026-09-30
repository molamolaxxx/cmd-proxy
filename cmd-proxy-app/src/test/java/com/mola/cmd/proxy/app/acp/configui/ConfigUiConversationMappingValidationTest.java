package com.mola.cmd.proxy.app.acp.configui;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ConfigUiConversationMappingValidationTest {
    @Test
    public void validatesMemberOwnershipAndDuplicateConversationsOnSave() {
        Map<String, Object> team = new HashMap<>();
        team.put("id", "team-1"); team.put("mode", "NORMAL");
        team.put("members", Collections.singletonList(Collections.singletonMap("id", "member-a")));
        ConfigUiServer server = new ConfigUiServer(0, () -> {}, ignored -> {},
                Collections::emptyMap, Collections::emptyMap, (id, enabled) -> true,
                () -> Collections.singletonList(team));
        JSONObject root = JSON.parseObject("{\"channels\":[{\"binding\":{\"type\":\"TEAM_MEMBER\","
                + "\"teamId\":\"team-1\",\"teamMemberSelection\":\"CONVERSATION_MAPPING\","
                + "\"conversationMappings\":[{\"chatType\":\"group\",\"conversationId\":\"chat\",\"teamMemberId\":\"member-a\"}]}}]}");
        assertNull(server.validateChannelConversationMappings(root));
        JSONObject binding = root.getJSONArray("channels").getJSONObject(0).getJSONObject("binding");
        // Validation may normalize the array to model objects, so round-trip before editing.
        root = JSON.parseObject(root.toJSONString());
        binding = root.getJSONArray("channels").getJSONObject(0).getJSONObject("binding");
        JSONObject mapping = binding.getJSONArray("conversationMappings").getJSONObject(0);
        mapping.put("teamMemberId", "other-team-member");
        assertEquals("conversation mapping Team member not found", server.validateChannelConversationMappings(root));
        mapping.put("teamMemberId", "member-a");
        binding.getJSONArray("conversationMappings").add(JSON.parseObject(mapping.toJSONString()));
        assertEquals("duplicate conversation mapping", server.validateChannelConversationMappings(root));
        binding.put("conversationMappings", Collections.emptyList());
        assertNull(server.validateChannelConversationMappings(root));
        team.put("mode", "CAPTAIN");
        assertTrue(server.validateChannelConversationMappings(root).contains("CAPTAIN_ONLY_BINDING"));
    }
}
