package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.task.model.TaskException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/** Resolves a user selected target to one stable execution assignee. */
public final class TaskTargetResolver {
    public static final String TYPE_AGENT = "AGENT";
    public static final String TYPE_TEAM = "TEAM";
    public static final String MODE_FIXED = "FIXED";
    public static final String MODE_RANDOM = "RANDOM";
    public static final String MODE_AFFINITY = "AFFINITY";

    private final TaskExecutionDirectory directory;
    private final Random random;

    public TaskTargetResolver(TaskExecutionDirectory directory) {
        this(directory, new Random());
    }

    TaskTargetResolver(TaskExecutionDirectory directory, Random random) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Resolves an assignee without persisting it. The caller must use TaskRepository.assign,
     * whose first-writer CAS makes the selected member durable before any delivery starts.
     */
    public JSONObject resolve(String taskId, JSONObject target,
                              List<JSONObject> activeAssignments) {
        requireText(taskId, "taskId");
        if (target == null) throw new IllegalArgumentException("target is required");
        String type = requireText(target.getString("type"), "target.type").toUpperCase();
        if (TYPE_AGENT.equals(type)) {
            JSONObject assignee = directory.resolveAgent(new JSONObject(target));
            return requireTrustedAssignee(assignee, "Agent target is unavailable");
        }
        if (!TYPE_TEAM.equals(type)) {
            throw new IllegalArgumentException("unsupported target.type: " + type);
        }
        return resolveTeam(taskId, target, activeAssignments == null
                ? Collections.emptyList() : activeAssignments);
    }

    /** Returns trusted candidates for TaskRepository's transactional selection primitive. */
    public List<JSONObject> candidates(JSONObject target) {
        if (target == null) throw new IllegalArgumentException("target is required");
        String type = requireText(target.getString("type"), "target.type").toUpperCase();
        if (TYPE_AGENT.equals(type)) {
            return Collections.singletonList(requireTrustedAssignee(
                    directory.resolveAgent(new JSONObject(target)),
                    "Agent target is unavailable"));
        }
        if (!TYPE_TEAM.equals(type)) {
            throw new IllegalArgumentException("unsupported target.type: " + type);
        }
        List<JSONObject> members = trustedTeamMembers(target);
        String mode = assignmentMode(target);
        validateCaptainTarget(target, members, mode);
        if (MODE_FIXED.equals(mode)) {
            String fixed = requireText(target.getString("teamMemberId"),
                    "target.teamMemberId");
            JSONObject selected = findMember(members, fixed);
            if (selected == null) {
                throw new IllegalStateException("fixed Team member is unavailable: " + fixed);
            }
            members = Collections.singletonList(selected);
        }
        List<JSONObject> result = new ArrayList<>();
        for (JSONObject member : members) {
            JSONObject copy = new JSONObject(member);
            copy.put("assignmentMode", mode);
            result.add(copy);
        }
        return Collections.unmodifiableList(result);
    }

    public String assignmentMode(JSONObject target) {
        if (target == null) throw new IllegalArgumentException("target is required");
        if (TYPE_AGENT.equalsIgnoreCase(target.getString("type"))) return MODE_FIXED;
        String mode = trim(target.getString("mode")).toUpperCase();
        if (mode.isEmpty()) mode = MODE_FIXED;
        if (!MODE_FIXED.equals(mode) && !MODE_RANDOM.equals(mode)
                && !MODE_AFFINITY.equals(mode)) {
            throw new IllegalArgumentException("unsupported Team assignment mode: " + mode);
        }
        return mode;
    }

    private JSONObject resolveTeam(String taskId, JSONObject target,
                                   List<JSONObject> activeAssignments) {
        String teamId = requireText(target.getString("teamId"), "target.teamId");
        List<JSONObject> members = trustedTeamMembers(target);
        String mode = assignmentMode(target);
        validateCaptainTarget(target, members, mode);
        JSONObject selected;
        if (MODE_FIXED.equals(mode)) {
            String fixed = requireText(target.getString("teamMemberId"),
                    "target.teamMemberId");
            selected = findMember(members, fixed);
            if (selected == null) {
                throw new IllegalStateException("fixed Team member is unavailable: " + fixed);
            }
        } else if (MODE_RANDOM.equals(mode)) {
            selected = members.get(random.nextInt(members.size()));
        } else if (MODE_AFFINITY.equals(mode)) {
            Set<String> occupied = occupiedMembers(teamId, taskId, activeAssignments);
            List<JSONObject> unoccupied = new ArrayList<>();
            for (JSONObject member : members) {
                if (!occupied.contains(member.getString("teamMemberId"))) {
                    unoccupied.add(member);
                }
            }
            List<JSONObject> candidates = unoccupied.isEmpty() ? members : unoccupied;
            selected = candidates.get(stableIndex(taskId, candidates.size()));
        } else {
            throw new IllegalArgumentException("unsupported Team assignment mode: " + mode);
        }
        JSONObject result = new JSONObject(selected);
        result.put("teamId", teamId);
        result.put("assignmentMode", mode);
        return requireTrustedAssignee(result, "Team member is unavailable");
    }

    private List<JSONObject> trustedTeamMembers(JSONObject target) {
        String teamId = requireText(target.getString("teamId"), "target.teamId");
        List<JSONObject> supplied = directory.listTeamMembers(new JSONObject(target));
        List<JSONObject> members = new ArrayList<>();
        if (supplied != null) {
            for (JSONObject member : supplied) {
                if (member == null) continue;
                String memberId = trim(member.getString("teamMemberId"));
                if (memberId.isEmpty()) {
                    throw new IllegalStateException("trusted Team member has no teamMemberId");
                }
                String memberTeamId = trim(member.getString("teamId"));
                if (!memberTeamId.isEmpty() && !teamId.equals(memberTeamId)) {
                    throw new IllegalStateException("trusted Team member belongs to another Team");
                }
                JSONObject copy = new JSONObject(member);
                copy.put("teamId", teamId);
                members.add(copy);
            }
        }
        members.sort(Comparator.comparing(value -> value.getString("teamMemberId")));
        if (members.isEmpty()) throw new IllegalStateException("Team has no assignable members");
        return members;
    }

    private static void validateCaptainTarget(JSONObject target,
                                              List<JSONObject> members,
                                              String assignmentMode) {
        if (members.isEmpty() || !"CAPTAIN".equals(
                members.get(0).getString("teamMode"))) return;
        String captainId = trim(members.get(0).getString("captainTeamMemberId"));
        String requestedId = trim(target.getString("teamMemberId"));
        if (!MODE_FIXED.equals(assignmentMode) || captainId.isEmpty()
                || !captainId.equals(requestedId)) {
            throw new TaskException("CAPTAIN_ONLY_TASK_TARGET",
                    "Captain Team tasks must use FIXED with the authoritative captain", 400);
        }
    }

    private static Set<String> occupiedMembers(String teamId, String taskId,
                                               List<JSONObject> assignments) {
        Set<String> occupied = new HashSet<>();
        for (JSONObject row : assignments) {
            if (row == null || taskId.equals(row.getString("taskId"))) continue;
            JSONObject assignee = row.getJSONObject("assignee");
            if (assignee == null) continue;
            if (teamId.equals(assignee.getString("teamId"))) {
                String memberId = trim(assignee.getString("teamMemberId"));
                if (!memberId.isEmpty()) occupied.add(memberId);
            }
        }
        return occupied;
    }

    private static JSONObject findMember(List<JSONObject> members, String memberId) {
        for (JSONObject member : members) {
            if (memberId.equals(member.getString("teamMemberId"))) return member;
        }
        return null;
    }

    private static int stableIndex(String key, int size) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            long value = 0L;
            for (int i = 0; i < 8; i++) value = (value << 8) | (digest[i] & 0xffL);
            return (int) ((value & Long.MAX_VALUE) % size);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static JSONObject requireTrustedAssignee(JSONObject assignee, String message) {
        if (assignee == null || assignee.isEmpty()) throw new IllegalStateException(message);
        return new JSONObject(assignee);
    }

    private static String requireText(String value, String field) {
        String normalized = trim(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
