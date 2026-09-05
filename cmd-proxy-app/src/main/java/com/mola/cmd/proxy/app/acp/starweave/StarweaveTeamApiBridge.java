package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mola.cmd.proxy.app.acp.team.TeamManager;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamCreateCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamDeleteCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCreateSpec;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamQuery;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamCommandResult;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberSourceDescriptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Starweave-owned facade over the shared Fast Team authority. */
public final class StarweaveTeamApiBridge {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int EVENT_CAPACITY = 2000;
    private static final Deque<JSONObject> EVENTS = new ArrayDeque<>();
    private static final java.util.concurrent.atomic.AtomicLong EVENT_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();
    private static final StarweaveUploadStore UPLOADS = new StarweaveUploadStore();
    private static volatile Runtime runtime;

    private StarweaveTeamApiBridge() { }

    public static void install(TeamManager manager, String instanceId,
                               Supplier<List<TeamMemberSourceDescriptor>> sources) {
        install(manager, instanceId, sources, null);
    }

    public static void install(TeamManager manager, String instanceId,
                               Supplier<List<TeamMemberSourceDescriptor>> sources,
                               StarweaveTeamGateway gateway) {
        runtime = new Runtime(Objects.requireNonNull(manager, "manager"),
                instanceId, Objects.requireNonNull(sources, "sources"), gateway);
    }

    public static void clear(TeamManager expected) {
        Runtime current = runtime;
        if (current != null && current.manager == expected) runtime = null;
        synchronized (EVENTS) { EVENTS.clear(); }
        EVENT_SEQUENCE.set(0L);
    }

    public static JSONObject list() {
        Runtime current = requireRuntime();
        if (current.gateway != null) {
            try {
                JSONObject data = current.gateway.query("list", new JSONObject(true));
                JSONArray teams = data.getJSONArray("teams");
                if (teams != null) {
                    for (int i = 0; i < teams.size(); i++) {
                        teams.getJSONObject(i).put("coordinated", true);
                    }
                    return success(data);
                }
            } catch (IllegalStateException ignored) {
                // Starweave remains fully usable in local-only deployments.
            }
        }
        JSONObject local = result(current.manager.list(new TeamQuery(
                TeamDefinition.SCHEMA_VERSION, current.ownerId, null, null)));
        JSONObject data = local.getJSONObject("data");
        JSONArray teams = data == null ? null : data.getJSONArray("teams");
        if (teams != null) {
            for (int i = 0; i < teams.size(); i++) {
                JSONObject team = teams.getJSONObject(i);
                if (team.getBooleanValue("mixedPlacement")) {
                    // A persisted local fragment is not an ordinary local Team. Keep all
                    // mutations fail-closed through the coordinator while it reconnects.
                    team.put("coordinated", true);
                }
            }
        }
        return local;
    }

    public static JSONObject sources() {
        Runtime current = requireRuntime();
        JSONArray values = new JSONArray();
        for (TeamMemberSourceDescriptor source : current.sources.get()) {
            JSONObject value = new JSONObject(true);
            value.put("ownerChatterId", source.getOwnerChatterId());
            value.put("sourceGroupId", source.getSourceGroupId());
            value.put("sourceRobotId", source.getSourceRobotId());
            value.put("robotName", source.getRobotName());
            value.put("displayName", source.getDisplayName());
            value.put("avatar", source.getAvatar());
            value.put("remark", source.getRemark());
            value.put("onlyTeamMember", source.isOnlyTeamMember());
            value.put("cmdProxyInstanceId", current.instanceId);
            value.put("transportGroup", current.transportGroup);
            value.put("sourceType", "STARWEAVE");
            value.put("sourceLabel", "本环境 · Starweave");
            value.put("coordinated", false);
            values.add(value);
        }
        if (current.gateway != null) {
            try {
                JSONArray coordinated = current.gateway.query(
                        "sources", new JSONObject(true)).getJSONArray("sources");
                if (coordinated != null) {
                    java.util.Set<String> identities = new java.util.HashSet<>();
                    for (Object item : values) {
                        JSONObject source = (JSONObject) item;
                        identities.add(sourceIdentity(source));
                    }
                    for (int i = 0; i < coordinated.size(); i++) {
                        JSONObject source = coordinated.getJSONObject(i);
                        source.put("coordinated", true);
                        String identity = sourceIdentity(source);
                        int existingIndex = indexOfSource(values, identity);
                        if (existingIndex >= 0) values.set(existingIndex, source);
                        else if (identities.add(identity)) values.add(source);
                    }
                }
            } catch (IllegalStateException ignored) {
                // No coordinator is a supported Starweave-only deployment mode.
            }
        }
        JSONObject result = new JSONObject(true);
        result.put("sources", values);
        return result;
    }

    public static JSONObject create(JSONObject request) {
        Runtime current = requireRuntime();
        JSONArray requestedMembers = request == null ? null : request.getJSONArray("members");
        if (requestedMembers == null || requestedMembers.isEmpty()
                || requestedMembers.size() > 6) {
            throw new IllegalArgumentException("members size must be between 1 and 6");
        }
        boolean coordinated = false;
        for (int i = 0; i < requestedMembers.size(); i++) {
            coordinated |= requestedMembers.getJSONObject(i)
                    .getBooleanValue("coordinated");
        }
        if (coordinated) {
            if (current.gateway == null) {
                throw new IllegalStateException("Starweave Team coordinator is unavailable");
            }
            return success(current.gateway.mutate("create", new JSONObject(request)));
        }
        List<TeamMemberSourceDescriptor> availableSources = current.sources.get();
        List<TeamMemberCreateSpec> members = new ArrayList<>();
        for (int i = 0; i < requestedMembers.size(); i++) {
            JSONObject requested = requestedMembers.getJSONObject(i);
            String groupId = requested.getString("sourceGroupId");
            if (groupId == null || groupId.trim().isEmpty()) {
                groupId = requested.getString("groupId");
            }
            groupId = required(groupId, "members.sourceGroupId");
            TeamMemberSourceDescriptor source = findSource(availableSources, groupId);
            String requestedRobotId = requested.getString("sourceRobotId");
            if (requestedRobotId != null && !requestedRobotId.trim().isEmpty()
                    && !source.getSourceRobotId().equals(requestedRobotId.trim())) {
                throw new IllegalArgumentException(
                        "members.sourceRobotId does not match sourceGroupId");
            }
            String memberId = requested.getString("teamMemberId");
            if (memberId == null || memberId.trim().isEmpty()) {
                memberId = "member-" + (i + 1) + "-" + UUID.randomUUID()
                        .toString().substring(0, 8);
            }
            members.add(new TeamMemberCreateSpec(memberId,
                    source.getSourceRobotId(), source.getSourceGroupId(), i,
                    requested.getString("remark")));
        }
        String requestId = textOr(request.getString("requestId"), UUID.randomUUID().toString());
        String teamId = textOr(request.getString("teamId"),
                "team-" + UUID.randomUUID().toString());
        String name = textOr(request.getString("name"), "Starweave Team");
        TeamCreateCommand command = new TeamCreateCommand(
                TeamDefinition.SCHEMA_VERSION, requestId, teamId,
                current.ownerId, name, members);
        return result(current.manager.create(command, current.transportGroup));
    }

    public static JSONObject delete(JSONObject request) {
        Runtime current = requireRuntime();
        if (request != null && request.getBooleanValue("coordinated")) {
            if (current.gateway == null) {
                throw new IllegalStateException("Starweave Team coordinator is unavailable");
            }
            return success(current.gateway.mutate("delete", new JSONObject(request)));
        }
        String requestId = textOr(request == null ? null : request.getString("requestId"),
                UUID.randomUUID().toString());
        TeamDeleteCommand command = new TeamDeleteCommand(
                TeamDefinition.SCHEMA_VERSION, requestId, current.ownerId,
                required(request == null ? null : request.getString("teamId"), "teamId"),
                request == null ? null : request.getLong("expectedVersion"));
        return result(current.manager.delete(command));
    }

    public static JSONObject member(JSONObject request) {
        Runtime current = requireRuntime();
        if (request == null) throw new IllegalArgumentException("request body is required");
        JSONObject commandValue = new JSONObject(request);
        commandValue.put("schemaVersion", TeamDefinition.SCHEMA_VERSION);
        commandValue.put("ownerChatterId", current.ownerId);
        List<StarweaveUploadStore.ResolvedUpload> resolvedUploads = new ArrayList<>();
        if ("send".equals(request.getString("action"))) {
            String teamId = required(request.getString("teamId"), "teamId");
            String memberId = required(request.getString("teamMemberId"), "teamMemberId");
            String sessionId = request.getBooleanValue("coordinated")
                    ? required(request.getString("sessionId"), "sessionId")
                    : currentSessionId(current, teamId, memberId,
                    request.getString("sessionId"));
            JSONArray uploadIds = request.getJSONArray("uploadIds");
            JSONArray files = new JSONArray();
            if (uploadIds != null) {
                if (uploadIds.size() > 10) {
                    throw new IllegalArgumentException("at most 10 uploads per message");
                }
                for (int i = 0; i < uploadIds.size(); i++) {
                    StarweaveUploadStore.ResolvedUpload upload = UPLOADS.resolve(
                            uploadIds.getString(i), uploadOwner(teamId, memberId),
                            sessionId, 0L);
                    resolvedUploads.add(upload);
                    JSONObject file = new JSONObject(true);
                    file.put(upload.fileName, java.util.Base64.getEncoder()
                            .encodeToString(upload.bytes));
                    files.add(file);
                }
            }
            commandValue.put("files", files);
        }
        if (request.getBooleanValue("coordinated")) {
            if (current.gateway == null) {
                throw new IllegalStateException("Starweave Team coordinator is unavailable");
            }
            JSONObject data = current.gateway.mutate("member", commandValue);
            for (StarweaveUploadStore.ResolvedUpload upload : resolvedUploads) {
                UPLOADS.delete(upload.uploadId);
            }
            return success(data);
        }
        TeamMemberCommand command = GSON.fromJson(
                commandValue.toJSONString(), TeamMemberCommand.class);
        String requestId = textOr(request.getString("requestId"), UUID.randomUUID().toString());
        String action = required(request.getString("action"), "action");
        TeamCommandResult result;
        switch (action) {
            case "send": result = current.manager.send(requestId, command); break;
            case "cancel": result = current.manager.cancel(requestId, command); break;
            case "newSession": result = current.manager.newSession(requestId, command); break;
            case "memoryDream": result = current.manager.memoryDream(requestId, command); break;
            case "listSessions": result = current.manager.listSessions(requestId, command); break;
            case "status": result = current.manager.getStatus(requestId, command); break;
            case "context": result = current.manager.getContextUsage(requestId, command); break;
            case "history": result = current.manager.getSessionHistory(requestId, command); break;
            case "restore": result = current.manager.restoreSession(requestId, command); break;
            default: throw new IllegalArgumentException("unsupported Team member action: " + action);
        }
        if (result.isAccepted()) {
            for (StarweaveUploadStore.ResolvedUpload upload : resolvedUploads) {
                UPLOADS.delete(upload.uploadId);
            }
        }
        return result(result);
    }

    public static JSONObject upload(JSONObject request) {
        Runtime current = requireRuntime();
        if (request == null) throw new IllegalArgumentException("request body is required");
        String teamId = required(request.getString("teamId"), "teamId");
        String memberId = required(request.getString("teamMemberId"), "teamMemberId");
        String sessionId = request.getBooleanValue("coordinated")
                ? required(request.getString("sessionId"), "sessionId")
                : currentSessionId(current, teamId, memberId,
                request.getString("sessionId"));
        return UPLOADS.stage(uploadOwner(teamId, memberId), sessionId, 0L,
                request.getString("fileName"), request.getString("contentBase64"));
    }

    public static JSONObject resources(JSONObject request, boolean preview) {
        Runtime current = requireRuntime();
        TeamMemberCommand command = resourceCommand(current, request);
        String requestId = textOr(request.getString("requestId"),
                UUID.randomUUID().toString());
        return result(preview
                ? current.manager.previewSessionResource(requestId, command,
                request.getString("resourceId"))
                : current.manager.listSessionResources(requestId, command));
    }

    public static com.mola.cmd.proxy.app.acp.team.TeamResourcePayload downloadResource(
            JSONObject request) {
        Runtime current = requireRuntime();
        return current.manager.downloadSessionResource(
                resourceCommand(current, request), request.getString("resourceId"));
    }

    private static TeamMemberCommand resourceCommand(Runtime current,
                                                      JSONObject request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        JSONObject value = new JSONObject(request);
        value.put("schemaVersion", TeamDefinition.SCHEMA_VERSION);
        value.put("ownerChatterId", current.ownerId);
        return GSON.fromJson(value.toJSONString(), TeamMemberCommand.class);
    }

    /** Returns true when the event belongs to Starweave and must not reach MolaChat. */
    public static boolean publishIfOwned(TeamEventEnvelope event) {
        Runtime current = runtime;
        if (current == null || event == null) return false;
        java.util.Optional<com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime> team =
                current.manager.getRuntime(event.getTeamId());
        if (!team.isPresent() || !current.ownerId.equals(
                team.get().getDefinition().getOwnerChatterId())) return false;
        JSONObject value = JSON.parseObject(GSON.toJson(event));
        value.put("teamEventSeq", event.getEventSeq());
        value.put("eventSeq", EVENT_SEQUENCE.incrementAndGet());
        synchronized (EVENTS) {
            EVENTS.addLast(value);
            while (EVENTS.size() > EVENT_CAPACITY) EVENTS.removeFirst();
            EVENTS.notifyAll();
        }
        return true;
    }

    /** Mixed Starweave fragments must also reach the MolaChat global coordinator. */
    public static boolean requiresCoordinator(TeamEventEnvelope event) {
        Runtime current = runtime;
        if (current == null || event == null) return false;
        java.util.Optional<com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime> team =
                current.manager.getRuntime(event.getTeamId());
        return team.isPresent() && current.ownerId.equals(
                team.get().getDefinition().getOwnerChatterId())
                && team.get().getDefinition().isMixedPlacement();
    }

    public static java.util.Map<String, String> acceptGatewayResult(
            String rpcRequestId, String[] args) {
        Runtime current = requireRuntime();
        if (current.gateway == null) {
            throw new IllegalStateException("Starweave Team coordinator is unavailable");
        }
        return current.gateway.acceptResult(rpcRequestId, args);
    }

    public static java.util.Map<String, String> acceptGatewayReady(
            String rpcRequestId, String[] args) {
        Runtime current = requireRuntime();
        if (current.gateway == null) {
            throw new IllegalStateException("Starweave Team coordinator is unavailable");
        }
        return current.gateway.acceptReady(rpcRequestId, args);
    }

    public static java.util.Map<String, String> acceptGatewayEvent(
            String rpcRequestId, String[] args) {
        if (args == null || args.length != 1) {
            throw new IllegalArgumentException("exactly one event argument is required");
        }
        Runtime current = requireRuntime();
        JSONObject wrapper = JSON.parseObject(args[0]);
        if (wrapper == null || !current.ownerId.equals(wrapper.getString("ownerChatterId"))) {
            throw new IllegalArgumentException("Starweave Team event owner mismatch");
        }
        JSONObject event = wrapper.getJSONObject("event");
        if (event == null) throw new IllegalArgumentException("event is required");
        String teamId = required(event.getString("teamId"), "event.teamId");
        com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime team = current.manager
                .getRuntime(teamId).orElseThrow(() ->
                        new IllegalArgumentException("coordinated Team not found"));
        TeamDefinition definition = team.getDefinition();
        if (!definition.isMixedPlacement()
                || !current.ownerId.equals(definition.getOwnerChatterId())) {
            throw new IllegalArgumentException("event does not belong to a mixed Starweave Team");
        }
        String memberId = event.getString("teamMemberId");
        if (memberId != null && !memberId.trim().isEmpty()
                && definition.getRoster().stream().noneMatch(contact ->
                memberId.equals(contact.getTargetTeamMemberId()))) {
            throw new IllegalArgumentException("event Team member is not in the roster");
        }
        appendEvent(event);
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        result.put("requestId", rpcRequestId == null ? "" : rpcRequestId);
        result.put("accepted", "true");
        result.put("code", "OK");
        return result;
    }

    public static JSONArray events(long afterSeq, String teamId, String teamMemberId) {
        validateEventTarget(teamId, teamMemberId);
        JSONArray result = new JSONArray();
        synchronized (EVENTS) {
            for (JSONObject event : EVENTS) {
                if (event.getLongValue("eventSeq") <= afterSeq) continue;
                if (teamId != null && !teamId.equals(event.getString("teamId"))) continue;
                if (teamMemberId != null
                        && !teamMemberId.equals(event.getString("teamMemberId"))) continue;
                result.add(new JSONObject(event));
            }
        }
        return result;
    }

    /** Waits for the next filtered Team event batch without making ConfigUI poll. */
    public static JSONArray awaitEvents(long afterSeq, String teamId,
                                        String teamMemberId, long timeoutMillis) {
        validateEventTarget(teamId, teamMemberId);
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        synchronized (EVENTS) {
            while (true) {
                JSONArray available = eventsLocked(afterSeq, teamId, teamMemberId);
                if (!available.isEmpty()) return available;
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) return available;
                try {
                    EVENTS.wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return available;
                }
            }
        }
    }

    private static JSONArray eventsLocked(long afterSeq, String teamId,
                                          String teamMemberId) {
        JSONArray result = new JSONArray();
        for (JSONObject event : EVENTS) {
            if (event.getLongValue("eventSeq") <= afterSeq) continue;
            if (teamId != null && !teamId.equals(event.getString("teamId"))) continue;
            if (teamMemberId != null
                    && !teamMemberId.equals(event.getString("teamMemberId"))) continue;
            result.add(new JSONObject(event));
        }
        return result;
    }

    private static void validateEventTarget(String teamId, String teamMemberId) {
        if (teamId == null || teamId.trim().isEmpty()) return;
        Runtime current = requireRuntime();
        com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime team = current.manager
                .getRuntime(teamId).orElseThrow(() ->
                        new IllegalArgumentException("Team not found"));
        if (!current.ownerId.equals(team.getDefinition().getOwnerChatterId())) {
            throw new IllegalArgumentException("Team does not belong to Starweave owner");
        }
        if (teamMemberId == null || teamMemberId.trim().isEmpty()) return;
        if (team.getDefinition().isMixedPlacement()) {
            for (com.mola.cmd.proxy.app.acp.team.model.TeamContactRef contact
                    : team.getDefinition().getRoster()) {
                if (teamMemberId.equals(contact.getTargetTeamMemberId())) return;
            }
            throw new IllegalArgumentException("Team member not found");
        }
        for (com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition member
                : team.getDefinition().getMembers()) {
            if (teamMemberId.equals(member.getTeamMemberId())) return;
        }
        throw new IllegalArgumentException("Team member not found");
    }

    private static String currentSessionId(Runtime current, String teamId,
                                           String memberId, String expectedSessionId) {
        com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime team = current.manager
                .getRuntime(teamId).orElseThrow(() ->
                        new IllegalArgumentException("Team not found"));
        if (!current.ownerId.equals(team.getDefinition().getOwnerChatterId())) {
            throw new IllegalArgumentException("Team does not belong to Starweave owner");
        }
        for (com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition member
                : team.getDefinition().getMembers()) {
            if (!memberId.equals(member.getTeamMemberId())) continue;
            String sessionId = required(member.getSessionId(), "member.sessionId");
            if (expectedSessionId != null && !expectedSessionId.trim().isEmpty()
                    && !sessionId.equals(expectedSessionId.trim())) {
                throw new IllegalArgumentException("Team member session has changed");
            }
            return sessionId;
        }
        throw new IllegalArgumentException("Team member not found");
    }

    private static String uploadOwner(String teamId, String memberId) {
        return "team:" + teamId + ":" + memberId;
    }

    private static JSONObject result(TeamCommandResult result) {
        return JSON.parseObject(GSON.toJson(result));
    }

    private static JSONObject success(JSONObject data) {
        JSONObject result = new JSONObject(true);
        result.put("accepted", true);
        result.put("code", "OK");
        result.put("message", "OK");
        result.put("data", data == null ? new JSONObject(true) : data);
        return result;
    }

    private static void appendEvent(JSONObject source) {
        JSONObject event = new JSONObject(source);
        synchronized (EVENTS) {
            String eventId = event.getString("eventId");
            if (eventId != null) {
                for (JSONObject existing : EVENTS) {
                    if (eventId.equals(existing.getString("eventId"))) return;
                }
            }
            event.put("teamEventSeq", event.getLongValue("eventSeq"));
            event.put("eventSeq", EVENT_SEQUENCE.incrementAndGet());
            EVENTS.addLast(event);
            while (EVENTS.size() > EVENT_CAPACITY) EVENTS.removeFirst();
            EVENTS.notifyAll();
        }
    }

    private static String sourceIdentity(JSONObject source) {
        return textOr(source.getString("cmdProxyInstanceId"), "") + "\n"
                + textOr(source.getString("sourceGroupId"), "") + "\n"
                + textOr(source.getString("sourceRobotId"), "");
    }

    private static int indexOfSource(JSONArray values, String identity) {
        for (int i = 0; i < values.size(); i++) {
            if (identity.equals(sourceIdentity(values.getJSONObject(i)))) return i;
        }
        return -1;
    }

    private static TeamMemberSourceDescriptor findSource(
            List<TeamMemberSourceDescriptor> sources, String groupId) {
        for (TeamMemberSourceDescriptor source : sources) {
            if (groupId.equals(source.getSourceGroupId())) return source;
        }
        throw new IllegalArgumentException(
                "Team member must reference an available Starweave Fast Team source");
    }

    private static Runtime requireRuntime() {
        Runtime current = runtime;
        if (current == null) throw new IllegalStateException("Starweave Team service is not running");
        return current;
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static final class Runtime {
        final TeamManager manager;
        final String ownerId;
        final String instanceId;
        final String transportGroup;
        final Supplier<List<TeamMemberSourceDescriptor>> sources;
        final StarweaveTeamGateway gateway;

        Runtime(TeamManager manager, String instanceId,
                Supplier<List<TeamMemberSourceDescriptor>> sources,
                StarweaveTeamGateway gateway) {
            this.manager = manager;
            this.instanceId = instanceId;
            this.ownerId = StarweaveIdentity.ownerId(instanceId);
            this.transportGroup = "starweave-team:" + instanceId;
            this.sources = sources;
            this.gateway = gateway;
        }
    }
}
