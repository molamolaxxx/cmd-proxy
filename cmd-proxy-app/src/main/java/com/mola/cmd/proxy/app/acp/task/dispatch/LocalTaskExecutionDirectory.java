package com.mola.cmd.proxy.app.acp.task.dispatch;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.team.TeamManager;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberState;
import com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Authoritative local configuration adapter for Agent and non-mixed Team targets. */
public final class LocalTaskExecutionDirectory implements TaskExecutionDirectory {
    private final String instanceId;
    private final String ownerId;
    private final Map<String, AcpRobotParam> robotsByGroupId;
    private final TeamManager teams;
    private final MixedTeamDirectory mixedTeams;

    public LocalTaskExecutionDirectory(String instanceId, String ownerId,
                                       Map<String, AcpRobotParam> robotsByGroupId,
                                       TeamManager teams, MixedTeamDirectory mixedTeams) {
        this.instanceId = required(instanceId, "instanceId");
        this.ownerId = required(ownerId, "ownerId");
        this.robotsByGroupId = Objects.requireNonNull(robotsByGroupId, "robotsByGroupId");
        this.teams = teams;
        this.mixedTeams = mixedTeams;
    }

    @Override
    public JSONObject resolveAgent(JSONObject target) {
        requireLocalIdentity(target);
        String requestedGroup = trim(target.getString("groupId"));
        String requestedAgent = trim(target.getString("agentId"));
        if (requestedAgent.isEmpty()) requestedAgent = trim(target.getString("robotId"));
        List<Map.Entry<String, AcpRobotParam>> candidates =
                new ArrayList<>(robotsByGroupId.entrySet());
        candidates.sort(Comparator.comparing(Map.Entry::getKey));
        for (Map.Entry<String, AcpRobotParam> entry : candidates) {
            AcpRobotParam robot = entry.getValue();
            if (robot == null || !robot.isEnabled()
                    || robot.isOnlySubAgent() || robot.isOnlyTeamMember()) continue;
            String robotId = robotId(robot.getName());
            if (!requestedGroup.isEmpty() && !requestedGroup.equals(entry.getKey())) continue;
            if (!requestedAgent.isEmpty() && !requestedAgent.equals(robotId)) continue;
            if (requestedGroup.isEmpty() && requestedAgent.isEmpty()) continue;
            JSONObject assignee = base(robotId, entry.getKey(), robot);
            assignee.put("surface", "STARWEAVE");
            return assignee;
        }
        return null;
    }

    @Override
    public List<JSONObject> listTeamMembers(JSONObject target) {
        requireLocalIdentity(target);
        if (teams == null) return Collections.emptyList();
        String teamId = required(target.getString("teamId"), "target.teamId");
        TeamRuntime runtime = teams.getRuntime(teamId).orElse(null);
        if (runtime == null || runtime.getDefinition().getState().isTerminal()) {
            return Collections.emptyList();
        }
        TeamDefinition team = runtime.getDefinition();
        if (!ownerId.equals(team.getOwnerChatterId())) {
            throw new IllegalArgumentException("Team owner does not match task target");
        }
        if (team.isMixedPlacement()) {
            if (mixedTeams == null || !mixedTeams.isAvailable()) {
                throw new IllegalStateException(
                        "Trusted coordinator roster is unavailable for mixed Team");
            }
            return applyTeamPolicy(team,
                    mixedTeams.listMembers(instanceId, ownerId, teamId));
        }
        List<JSONObject> result = new ArrayList<>();
        for (TeamMemberDefinition member : team.getMembers()) {
            if (member.getState() == TeamMemberState.CLOSING
                    || member.getState() == TeamMemberState.CLOSED) continue;
            JSONObject assignee = new JSONObject(true);
            assignee.put("instanceId", instanceId);
            assignee.put("ownerId", ownerId);
            assignee.put("surface", "STARWEAVE_TEAM");
            assignee.put("teamId", teamId);
            assignee.put("teamMemberId", member.getTeamMemberId());
            assignee.put("acpClientId", member.getAcpClientId());
            assignee.put("agentId", member.getSourceRobotId());
            assignee.put("robotId", member.getSourceRobotId());
            assignee.put("groupId", member.getSourceGroupId());
            assignee.put("robotNameSnapshot", member.getSourceRobotName());
            assignee.put("displayNameSnapshot", member.getDisplayName());
            assignee.put("placement", "LOCAL");
            result.add(assignee);
        }
        return applyTeamPolicy(team, result);
    }

    private static List<JSONObject> applyTeamPolicy(TeamDefinition team,
                                                    List<JSONObject> members) {
        List<JSONObject> result = new ArrayList<>();
        if (members != null) {
            for (JSONObject member : members) {
                if (member == null) continue;
                if (team.isCaptainMode() && !team.isCaptain(
                        member.getString("teamMemberId"))) continue;
                JSONObject copy = new JSONObject(member);
                copy.put("teamMode", team.getMode().name());
                copy.put("captainTeamMemberId", team.getCaptainTeamMemberId());
                result.add(copy);
            }
        }
        if (team.isCaptainMode() && result.isEmpty()) {
            throw new IllegalStateException("CAPTAIN_REQUIRED: captain is unavailable");
        }
        return Collections.unmodifiableList(result);
    }

    private JSONObject base(String robotId, String groupId, AcpRobotParam robot) {
        JSONObject assignee = new JSONObject(true);
        assignee.put("instanceId", instanceId);
        assignee.put("ownerId", ownerId);
        // agentId is the v1 contract name; robotId mirrors the current runtime vocabulary.
        assignee.put("agentId", robotId);
        assignee.put("robotId", robotId);
        assignee.put("groupId", groupId);
        assignee.put("robotNameSnapshot", robot.getName());
        assignee.put("providerSnapshot", robot.getAgentProvider());
        assignee.put("placement", "LOCAL");
        return assignee;
    }

    private void requireLocalIdentity(JSONObject target) {
        String requestedInstance = trim(target.getString("instanceId"));
        if (!requestedInstance.isEmpty() && !instanceId.equals(requestedInstance)) {
            throw new IllegalArgumentException("Target belongs to another instance");
        }
        String requestedOwner = trim(target.getString("ownerId"));
        if (!requestedOwner.isEmpty() && !ownerId.equals(requestedOwner)) {
            throw new IllegalArgumentException("Target belongs to another owner");
        }
    }

    static String robotId(String robotName) {
        return "acp-" + required(robotName, "robotName")
                .replace(" ", "_").replace("\u3000", "_");
    }

    private static String required(String value, String field) {
        String normalized = trim(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public interface MixedTeamDirectory {
        boolean isAvailable();

        /** Returns a trusted full roster with stable participant instance IDs. */
        List<JSONObject> listMembers(String homeInstanceId, String ownerId, String teamId);
    }
}
