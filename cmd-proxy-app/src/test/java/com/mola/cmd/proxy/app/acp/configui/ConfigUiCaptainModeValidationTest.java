package com.mola.cmd.proxy.app.acp.configui;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ConfigUiCaptainModeValidationTest {

    private final ConfigUiServer server = new ConfigUiServer(0, () -> { }, ignored -> { },
            Collections::emptyMap, Collections::emptyMap,
            (channelId, enabled) -> true,
            () -> Collections.singletonList(captainTeam()));

    @Test
    public void rejectsAutomaticChannelBindingForCaptainTeam() {
        JSONObject root = JSON.parseObject("{\"channels\":[{\"binding\":{" +
                "\"type\":\"TEAM_MEMBER\",\"teamId\":\"team-1\"," +
                "\"teamMemberSelection\":\"AFFINITY\"}}]}");

        assertEquals("CAPTAIN_ONLY_BINDING: captain Team must use the fixed captain member",
                server.validateCaptainTeamEntrypoints(root));
    }

    @Test
    public void rejectsNonCaptainExternalTaskTarget() {
        JSONObject root = JSON.parseObject("{\"externalTaskApis\":[{\"target\":{" +
                "\"type\":\"TEAM\",\"teamId\":\"team-1\",\"mode\":\"FIXED\"," +
                "\"teamMemberId\":\"member-2\"}}]}");

        assertEquals("CAPTAIN_ONLY_TASK_TARGET: captain Team must use the fixed captain member",
                server.validateCaptainTeamEntrypoints(root));
    }

    @Test
    public void acceptsFixedCaptainEntrypoints() {
        JSONObject root = JSON.parseObject("{\"channels\":[{\"binding\":{" +
                "\"type\":\"TEAM_MEMBER\",\"teamId\":\"team-1\"," +
                "\"teamMemberSelection\":\"FIXED\",\"teamMemberId\":\"member-1\"}}]," +
                "\"externalTaskApis\":[{\"target\":{\"type\":\"TEAM\"," +
                "\"teamId\":\"team-1\",\"mode\":\"FIXED\"," +
                "\"teamMemberId\":\"member-1\"}}]}");

        assertNull(server.validateCaptainTeamEntrypoints(root));
    }

    private static Map<String, Object> captainTeam() {
        Map<String, Object> team = new LinkedHashMap<>();
        team.put("id", "team-1");
        team.put("mode", "CAPTAIN");
        team.put("captainTeamMemberId", "member-1");
        return team;
    }
}
