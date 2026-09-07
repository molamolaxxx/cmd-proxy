package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class TaskTargetResolverTest {

    @Test
    public void agentResolutionUsesTrustedDirectoryResult() {
        Directory directory = new Directory();
        directory.agent = json("instanceId", "trusted-instance", "robotId", "acp-a");
        TaskTargetResolver resolver = new TaskTargetResolver(directory, new Random(1L));
        JSONObject target = json("type", "AGENT", "instanceId", "spoofed");

        JSONObject result = resolver.resolve("task-1", target, Collections.emptyList());

        assertEquals("trusted-instance", result.getString("instanceId"));
        assertEquals("acp-a", result.getString("robotId"));
    }

    @Test
    public void fixedAssignmentKeepsRequestedMember() {
        Directory directory = team("team-1", "member-b", "member-a");
        TaskTargetResolver resolver = new TaskTargetResolver(directory, new Random(1L));
        JSONObject target = json("type", "TEAM", "teamId", "team-1",
                "mode", "FIXED", "teamMemberId", "member-b");

        JSONObject result = resolver.resolve("task-1", target, Collections.emptyList());

        assertEquals("member-b", result.getString("teamMemberId"));
        assertEquals("FIXED", result.getString("assignmentMode"));
    }

    @Test
    public void affinityPrefersUnoccupiedAndIsDeterministic() {
        Directory directory = team("team-1", "member-c", "member-a", "member-b");
        TaskTargetResolver resolver = new TaskTargetResolver(directory, new Random(1L));
        JSONObject target = json("type", "TEAM", "teamId", "team-1", "mode", "AFFINITY");
        List<JSONObject> active = Arrays.asList(
                assignment("other-a", "team-1", "member-a"),
                assignment("other-b", "team-1", "member-b"));

        JSONObject first = resolver.resolve("task-stable", target, active);
        JSONObject second = resolver.resolve("task-stable", target, active);

        assertEquals("member-c", first.getString("teamMemberId"));
        assertEquals(first, second);
    }

    @Test
    public void affinityHashesAcrossStableRosterWhenAllMembersOccupied() {
        Directory directory = team("team-1", "member-b", "member-a");
        TaskTargetResolver resolver = new TaskTargetResolver(directory, new Random(1L));
        JSONObject target = json("type", "TEAM", "teamId", "team-1", "mode", "AFFINITY");
        List<JSONObject> active = Arrays.asList(
                assignment("other-a", "team-1", "member-a"),
                assignment("other-b", "team-1", "member-b"));

        String first = resolver.resolve("task-stable", target, active)
                .getString("teamMemberId");
        String second = resolver.resolve("task-stable", target, active)
                .getString("teamMemberId");

        assertEquals(first, second);
    }

    @Test
    public void captainTeamRejectsAutomaticAndNonCaptainTargets() {
        Directory directory = team("team-1", "captain");
        directory.members.get(0).put("teamMode", "CAPTAIN");
        directory.members.get(0).put("captainTeamMemberId", "captain");
        TaskTargetResolver resolver = new TaskTargetResolver(directory, new Random(1L));

        assertCaptainFailure(resolver, json("type", "TEAM", "teamId", "team-1",
                "mode", "RANDOM"));
        assertCaptainFailure(resolver, json("type", "TEAM", "teamId", "team-1",
                "mode", "FIXED", "teamMemberId", "ordinary"));
        assertEquals("captain", resolver.resolve("task-1", json("type", "TEAM",
                "teamId", "team-1", "mode", "FIXED", "teamMemberId", "captain"),
                Collections.emptyList()).getString("teamMemberId"));
    }

    private static void assertCaptainFailure(TaskTargetResolver resolver, JSONObject target) {
        try {
            resolver.resolve("task-1", target, Collections.emptyList());
            fail("expected captain target rejection");
        } catch (com.mola.cmd.proxy.app.acp.task.model.TaskException expected) {
            assertEquals("CAPTAIN_ONLY_TASK_TARGET", expected.getCode());
        }
    }

    private static Directory team(String teamId, String... ids) {
        Directory directory = new Directory();
        for (String id : ids) {
            directory.members.add(json("teamId", teamId, "teamMemberId", id,
                    "displayName", id));
        }
        return directory;
    }

    private static JSONObject assignment(String taskId, String teamId, String memberId) {
        JSONObject value = json("taskId", taskId);
        value.put("assignee", json("teamId", teamId, "teamMemberId", memberId));
        return value;
    }

    private static JSONObject json(Object... values) {
        JSONObject result = new JSONObject(true);
        for (int i = 0; i < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }

    private static final class Directory implements TaskExecutionDirectory {
        private JSONObject agent;
        private final List<JSONObject> members = new ArrayList<>();

        @Override
        public JSONObject resolveAgent(JSONObject target) {
            return agent;
        }

        @Override
        public List<JSONObject> listTeamMembers(JSONObject target) {
            return members;
        }
    }
}
