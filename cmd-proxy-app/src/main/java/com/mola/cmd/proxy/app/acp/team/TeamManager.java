package com.mola.cmd.proxy.app.acp.team;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.PromptOptions;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamError;
import com.mola.cmd.proxy.app.acp.team.model.TeamErrorCode;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMetricsSnapshot;
import com.mola.cmd.proxy.app.acp.team.model.TeamOperationRecord;
import com.mola.cmd.proxy.app.acp.team.model.TeamResourceSnapshot;
import com.mola.cmd.proxy.app.acp.team.model.TeamState;
import com.mola.cmd.proxy.app.acp.team.model.TeamTombstone;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventSink;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventType;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamCommandResult;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamCreateCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamDeleteCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCreateSpec;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamQuery;
import com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime;
import com.mola.cmd.proxy.app.acp.team.talkto.TeamTalkToDispatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.BiPredicate;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Team 权威状态的最小生命周期容器。
 *
 * <p>本阶段只实现恢复、占位、查询和统一关闭；create/list/get command
 * 及 AcpClient 启动编排由后续 FT-CMD 项实现。</p>
 */
public final class TeamManager implements AutoCloseable {

    private static final Logger logger =
            LoggerFactory.getLogger(TeamManager.class);

    private final TeamStore store;
    private final TeamClientRegistry clientRegistry;
    private final TeamSourceRobotResolver sourceResolver;
    private final TeamEventSink eventSink;
    private final TeamStartupCoordinator startupCoordinator;
    private final ConcurrentHashMap<String, TeamRuntime> teams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> requestLocks = new ConcurrentHashMap<>();
    private final Object createQuotaLock = new Object();
    private final ConcurrentHashMap<String, TeamTalkToDispatcher> talkToDispatchers =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Consumer<String> scheduleCleanup = ignored -> { };
    private volatile Consumer<Set<String>> scheduleOrphanCleanup = ignored -> { };
    private volatile IntSupplier scheduleOwnerCount = () -> 0;
    private volatile ToIntFunction<String> scheduleTeamOwnerCount = ignored -> 0;
    private volatile BiPredicate<String, String> memoryDreamTrigger =
            (sourceGroupId, workspacePath) -> false;
    private final TeamHistoryArchiver historyArchiver;
    private final long cleanupTimeoutMillis;
    private final ExecutorService cleanupExecutor;
    private final ConcurrentHashMap<String, Future<List<IOException>>> pendingCleanup =
            new ConcurrentHashMap<>();
    private final TeamResourceReaper resourceReaper;
    private final TeamLimits limits;
    private final TeamMetrics metrics = new TeamMetrics();
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    private static final long OPERATION_TTL_MILLIS = 24L * 60L * 60L * 1000L;
    private static final long TOMBSTONE_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static final long DEFAULT_CLEANUP_TIMEOUT_MILLIS = 30_000L;

    public TeamManager(TeamStore store, TeamClientRegistry clientRegistry) {
        this(store, clientRegistry, spec -> {
            throw new TeamSourceResolutionException(
                    TeamErrorCode.SOURCE_ROBOT_NOT_FOUND, "source resolver is not configured");
        }, TeamEventSink.NOOP, null);
    }

    public TeamManager(TeamStore store, TeamClientRegistry clientRegistry,
                       TeamSourceRobotResolver sourceResolver) {
        this(store, clientRegistry, sourceResolver, TeamEventSink.NOOP, null);
    }

    public TeamManager(TeamStore store, TeamClientRegistry clientRegistry,
                       TeamSourceRobotResolver sourceResolver, TeamEventSink eventSink) {
        this(store, clientRegistry, sourceResolver, eventSink, null);
    }

    public TeamManager(TeamStore store, TeamClientRegistry clientRegistry,
                       TeamSourceRobotResolver sourceResolver, TeamEventSink eventSink,
                       TeamStartupCoordinator startupCoordinator) {
        this(store, clientRegistry, sourceResolver, eventSink, startupCoordinator,
                new TeamHistoryArchiver(), DEFAULT_CLEANUP_TIMEOUT_MILLIS,
                TeamLimits.system());
    }

    TeamManager(TeamStore store, TeamClientRegistry clientRegistry,
                TeamSourceRobotResolver sourceResolver, TeamEventSink eventSink,
                TeamStartupCoordinator startupCoordinator,
                TeamHistoryArchiver historyArchiver, long cleanupTimeoutMillis) {
        this(store, clientRegistry, sourceResolver, eventSink,
                startupCoordinator, historyArchiver, cleanupTimeoutMillis,
                TeamLimits.system());
    }

    TeamManager(TeamStore store, TeamClientRegistry clientRegistry,
                TeamSourceRobotResolver sourceResolver, TeamEventSink eventSink,
                TeamStartupCoordinator startupCoordinator,
                TeamHistoryArchiver historyArchiver, long cleanupTimeoutMillis,
                TeamLimits limits) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.clientRegistry = java.util.Objects.requireNonNull(clientRegistry, "clientRegistry");
        this.sourceResolver = java.util.Objects.requireNonNull(sourceResolver, "sourceResolver");
        this.eventSink = java.util.Objects.requireNonNull(eventSink, "eventSink");
        this.startupCoordinator = startupCoordinator;
        this.historyArchiver = java.util.Objects.requireNonNull(
                historyArchiver, "historyArchiver");
        if (cleanupTimeoutMillis < 1L) {
            throw new IllegalArgumentException("cleanupTimeoutMillis must be positive");
        }
        this.cleanupTimeoutMillis = cleanupTimeoutMillis;
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
        this.cleanupExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "team-resource-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        this.resourceReaper = new TeamResourceReaper(this);
    }

    public int recoverPersistedDefinitions() throws IOException {
        ensureOpen();
        List<TeamDefinition> persistedDefinitions = store.loadTeams();
        logger.info("team_recovery action=load root={} persistedTeams={}",
                store.getTeamsRoot(), persistedDefinitions.size());
        int added = 0;
        for (TeamDefinition definition : persistedDefinitions) {
            logger.info("team_recovery action=inspect teamId={} ownerChatterId={}"
                            + " state={} version={} transportGroup={}",
                    definition.getTeamId(), definition.getOwnerChatterId(),
                    definition.getState(), definition.getVersion(),
                    definition.getTransportGroup());
            if (definition.getState().isTerminal()) {
                cleanupEphemeralResourcesForDelete(definition.getTeamId());
                clientRegistry.closeTeam(definition.getTeamId());
                store.deleteTeam(definition.getTeamId());
                continue;
            }
            TeamDefinition recovered = definition;
            if (definition.getState() == TeamState.READY) {
                metrics.recoveryStarted();
                recovered = definition.transitionTo(
                        TeamState.RECOVERING, null, System.currentTimeMillis());
                store.saveTeam(recovered);
            }
            TeamRuntime runtime = new TeamRuntime(recovered);
            if (teams.putIfAbsent(definition.getTeamId(), runtime) == null) {
                added++;
                if (recovered.getState() == TeamState.CREATING
                        || recovered.getState() == TeamState.RECOVERING) {
                    if (recovered.getState() == TeamState.RECOVERING) {
                        publishRecoveryEvent(runtime,
                                TeamEventType.TEAM_RECOVERY_STARTED, null);
                    }
                    startMembers(runtime);
                } else if (recovered.getState() == TeamState.DELETING) {
                    runtime.stopAcceptingRequests();
                    cleanupExecutor.submit(() -> resumeDeleting(runtime));
                } else if (recovered.getState() == TeamState.FAILED) {
                    runtime.stopAcceptingRequests();
                }
            }
        }
        return added;
    }

    public void startResourceReaper() {
        resourceReaper.start();
    }

    /**
     * 仅供后续 create 状态机在成功持久化后发布 runtime。
     */
    boolean attachPersistedDefinition(TeamDefinition definition) {
        ensureOpen();
        return teams.putIfAbsent(definition.getTeamId(),
                new TeamRuntime(definition)) == null;
    }

    public Optional<TeamRuntime> getRuntime(String teamId) {
        return Optional.ofNullable(teams.get(teamId));
    }

    public List<TeamDefinition> snapshotDefinitions() {
        List<TeamDefinition> result = new ArrayList<>();
        for (TeamRuntime runtime : teams.values()) {
            result.add(runtime.getDefinition());
        }
        result.sort(Comparator.comparing(TeamDefinition::getTeamId));
        return Collections.unmodifiableList(result);
    }

    public TeamCommandResult create(TeamCreateCommand command, String transportGroup) {
        String requestId = command == null ? "" : command.getRequestId();
        try {
            validateCreate(command, transportGroup);
        } catch (IllegalArgumentException e) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.VALIDATION_ERROR, e.getMessage());
        }

        String payloadHash = sha256(gson.toJson(command));
        synchronized (requestLocks.computeIfAbsent(requestId, ignored -> new Object())) {
            try {
                Optional<TeamOperationRecord> existingOperation =
                        store.loadOperation(requestId);
                if (existingOperation.isPresent()) {
                    TeamOperationRecord operation = existingOperation.get();
                    if (!payloadHash.equals(operation.getPayloadHash())) {
                        return TeamCommandResult.error(requestId,
                                TeamErrorCode.IDEMPOTENCY_CONFLICT,
                                "requestId already used with different payload");
                    }
                    if (operation.getResultSnapshot() != null
                            && !operation.getResultSnapshot().trim().isEmpty()) {
                        return TeamCommandResult.fromSnapshotJson(
                                operation.getResultSnapshot());
                    }
                } else {
                    store.saveOperation(new TeamOperationRecord(
                            requestId, TeamOperationRecord.Operation.CREATE,
                            payloadHash, command.getTeamId(),
                            TeamOperationRecord.Status.ACCEPTED, null,
                            System.currentTimeMillis(),
                            System.currentTimeMillis() + 24L * 60L * 60L * 1000L));
                }

                synchronized (createQuotaLock) {
                    Optional<TeamDefinition> persisted =
                            store.loadTeam(command.getTeamId());
                    if (persisted.isPresent()) {
                        TeamDefinition definition = persisted.get();
                        if (!requestId.equals(definition.getCreateRequestId())) {
                            return finishOperation(command, payloadHash,
                                    TeamCommandResult.error(requestId,
                                            TeamErrorCode.IDEMPOTENCY_CONFLICT,
                                            "teamId already exists with another create request"),
                                    TeamOperationRecord.Status.FAILED);
                        }
                        attachPersistedDefinition(definition);
                        return finishOperation(command, payloadHash,
                                TeamCommandResult.success(requestId, "ALREADY_EXISTS",
                                        "Team already created by this request",
                                        definition.getVersion(), definition),
                                TeamOperationRecord.Status.SUCCEEDED);
                    }

                    TeamCommandResult quotaError = quotaError(command);
                    if (quotaError != null) {
                        metrics.createQuotaRejected();
                        logger.warn("team_lifecycle action=create_rejected code=QUOTA_EXCEEDED"
                                        + " teamId={} requestId={} activeTeams={}"
                                        + " activeMembers={} requestedMembers={}",
                                command.getTeamId(), requestId, activeTeamCount(),
                                activeMemberCount(), command.getMembers().size());
                        return finishOperation(command, payloadHash, quotaError,
                                TeamOperationRecord.Status.FAILED);
                    }

                    List<TeamMemberDefinition> members =
                            resolveMembers(command.getMembers());
                    long now = System.currentTimeMillis();
                    TeamDefinition definition = TeamDefinition.creating(
                            command.getTeamId(), command.getOwnerChatterId(),
                            command.getName(), transportGroup, requestId, members, now);
                    store.saveTeam(definition);
                    if (!attachPersistedDefinition(definition)) {
                        throw new IllegalStateException("Team runtime already exists");
                    }
                    TeamCommandResult result = finishOperation(command, payloadHash,
                            TeamCommandResult.success(requestId, "ACCEPTED",
                                    "Team definition persisted; member startup pending",
                                    definition.getVersion(), definition),
                            TeamOperationRecord.Status.SUCCEEDED);
                    TeamRuntime runtime = teams.get(definition.getTeamId());
                    metrics.createAccepted();
                    logger.info("team_lifecycle action=create_accepted teamId={}"
                                    + " requestId={} state={} version={} members={}",
                            definition.getTeamId(), requestId,
                            definition.getState(), definition.getVersion(),
                            definition.getMembers().size());
                    try {
                        eventSink.publish(TeamEventEnvelope.next(runtime, null, null,
                                TeamEventType.TEAM_CREATE_ACCEPTED, definition));
                    } catch (RuntimeException ignored) {
                        // callback 是投影通知，失败不能回滚已持久化的权威 Team 定义。
                    }
                    startMembers(runtime);
                    return result;
                }
            } catch (TeamSourceResolutionException e) {
                logger.warn("team_lifecycle action=create_source_rejected teamId={}"
                                + " requestId={} code={} sources={} error={}",
                        command == null ? "" : command.getTeamId(), requestId,
                        e.getCode(), command == null || command.getMembers() == null
                                ? Collections.emptyList()
                                : command.getMembers().stream()
                                .map(member -> member.getSourceGroupId() + "/"
                                        + member.getSourceRobotId())
                                .collect(java.util.stream.Collectors.toList()),
                        e.getMessage());
                return finishOperationQuietly(command, payloadHash,
                        TeamCommandResult.error(requestId, e.getCode(), e.getMessage()));
            } catch (TeamStore.VersionConflictException e) {
                return finishOperationQuietly(command, payloadHash,
                        TeamCommandResult.error(requestId,
                                TeamErrorCode.VERSION_CONFLICT, e.getMessage()));
            } catch (Exception e) {
                return finishOperationQuietly(command, payloadHash,
                        TeamCommandResult.error(requestId,
                                TeamErrorCode.INTERNAL_ERROR, e.getMessage()));
            }
        }
    }

    public TeamCommandResult delete(TeamDeleteCommand command) {
        String requestId = command == null ? "" : command.getRequestId();
        try {
            validateDelete(command);
        } catch (IllegalArgumentException e) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.VALIDATION_ERROR, e.getMessage());
        }
        String payloadHash = sha256(gson.toJson(command));
        synchronized (requestLocks.computeIfAbsent(
                requestId, ignored -> new Object())) {
            try {
                Optional<TeamOperationRecord> existing =
                        store.loadOperation(requestId);
                if (existing.isPresent()) {
                    TeamOperationRecord operation = existing.get();
                    if (operation.getOperation()
                            != TeamOperationRecord.Operation.DELETE
                            || !payloadHash.equals(operation.getPayloadHash())) {
                        return TeamCommandResult.error(requestId,
                                TeamErrorCode.IDEMPOTENCY_CONFLICT,
                                "requestId already used with different operation or payload");
                    }
                    if (operation.getResultSnapshot() != null
                            && !operation.getResultSnapshot().trim().isEmpty()) {
                        return TeamCommandResult.fromSnapshotJson(
                                operation.getResultSnapshot());
                    }
                }

                TeamRuntime runtime = teams.get(command.getTeamId());
                if (runtime == null) {
                    Optional<TeamDefinition> persisted =
                            store.loadTeam(command.getTeamId());
                    if (!persisted.isPresent()) {
                        return TeamCommandResult.error(requestId,
                                TeamErrorCode.NOT_FOUND, "Team not found");
                    }
                    attachPersistedDefinition(persisted.get());
                    runtime = teams.get(command.getTeamId());
                }
                TeamDefinition current = runtime.getDefinition();
                if (!command.getOwnerChatterId().equals(
                        current.getOwnerChatterId())) {
                    return TeamCommandResult.error(requestId,
                            TeamErrorCode.UNAUTHORIZED,
                            "Team does not belong to ownerChatterId");
                }
                if (command.getExpectedVersion() != null
                        && command.getExpectedVersion().longValue()
                        != current.getVersion()
                        && current.getState() != TeamState.DELETING) {
                    return TeamCommandResult.error(requestId,
                            TeamErrorCode.VERSION_CONFLICT,
                            "expectedVersion does not match current Team version");
                }
                if (!existing.isPresent()) {
                    store.saveOperation(new TeamOperationRecord(
                            requestId, TeamOperationRecord.Operation.DELETE,
                            payloadHash, command.getTeamId(),
                            TeamOperationRecord.Status.ACCEPTED, null,
                            System.currentTimeMillis(),
                            System.currentTimeMillis() + OPERATION_TTL_MILLIS));
                }

                runtime.getOperationLock().lock();
                try {
                    current = runtime.getDefinition();
                    if (current.getState().isTerminal()) {
                        return finishDeleteOperation(command, payloadHash,
                                deletedResult(requestId, current,
                                        Collections.emptyList(),
                                        current.getDeletedAt() == null
                                                ? System.currentTimeMillis()
                                                : current.getDeletedAt()
                                                + TOMBSTONE_TTL_MILLIS),
                                TeamOperationRecord.Status.SUCCEEDED);
                    }
                    if (current.getState() != TeamState.DELETING) {
                        runtime.stopAcceptingRequests();
                        TeamDefinition deleting = current.beginDeleting(
                                requestId, System.currentTimeMillis());
                        store.saveTeam(deleting);
                        if (!runtime.publishNextDefinition(deleting)) {
                            throw new IOException(
                                    "Team version changed before DELETING publish");
                        }
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("team", deleting);
                        data.put("previousState", current.getState().name());
                        data.put("expectedVersion", command.getExpectedVersion());
                        publishEvent(runtime,
                                TeamEventType.TEAM_DELETE_ACCEPTED, data);
                    } else if (!requestId.equals(
                            current.getDeleteRequestId())) {
                        return finishDeleteOperation(
                                command, payloadHash,
                                TeamCommandResult.error(requestId,
                                        TeamErrorCode.TEAM_DELETING,
                                        "Team deletion is already owned by another request"),
                                TeamOperationRecord.Status.FAILED);
                    }
                    TeamCommandResult result = finalizeDelete(
                            runtime, requestId);
                    return finishDeleteOperation(command, payloadHash, result,
                            result.isAccepted()
                                    ? TeamOperationRecord.Status.SUCCEEDED
                                    : TeamOperationRecord.Status.FAILED);
                } finally {
                    runtime.getOperationLock().unlock();
                }
            } catch (Exception e) {
                TeamRuntime runtime = teams.get(command.getTeamId());
                TeamError error = TeamError.of(
                        TeamErrorCode.INTERNAL_ERROR, safeMessage(e), true);
                if (runtime != null) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("team", runtime.getDefinition());
                    data.put("error", error);
                    publishEvent(runtime, TeamEventType.TEAM_DELETE_FAILED, data);
                }
                TeamCommandResult result = TeamCommandResult.error(
                        requestId, TeamErrorCode.INTERNAL_ERROR, safeMessage(e));
                metrics.deleteFailed();
                logger.warn("team_lifecycle action=delete_failed teamId={}"
                                + " requestId={} error={}",
                        command.getTeamId(), requestId, safeMessage(e));
                try {
                    return finishDeleteOperation(
                            command, payloadHash, result,
                            TeamOperationRecord.Status.FAILED);
                } catch (IOException ignored) {
                    return result;
                }
            }
        }
    }

    public TeamCommandResult list(TeamQuery query) {
        try {
            ensureOpen();
            validateQuery(query, false);
            List<TeamDefinition> definitions = snapshotDefinitions();
            List<TeamDefinition> visible = new ArrayList<>();
            long snapshotVersion = 0L;
            for (TeamDefinition definition : definitions) {
                if (query.getOwnerChatterId().equals(definition.getOwnerChatterId())) {
                    visible.add(definition);
                    snapshotVersion = Math.max(snapshotVersion, definition.getVersion());
                }
            }
            logger.info("team_query action=list ownerChatterId={} totalTeams={}"
                            + " matchedTeams={} matchedTeamIds={}",
                    query.getOwnerChatterId(), definitions.size(), visible.size(),
                    visible.stream().map(TeamDefinition::getTeamId)
                            .collect(java.util.stream.Collectors.toList()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("teams", visible);
            data.put("snapshotVersion", snapshotVersion);
            return TeamCommandResult.success("", "OK", "Team list loaded",
                    snapshotVersion, data);
        } catch (IllegalArgumentException e) {
            return TeamCommandResult.error("", TeamErrorCode.VALIDATION_ERROR, e.getMessage());
        }
    }

    public TeamCommandResult get(TeamQuery query) {
        try {
            ensureOpen();
            validateQuery(query, true);
            Optional<TeamRuntime> runtime = getRuntime(query.getTeamId());
            if (!runtime.isPresent()) {
                return TeamCommandResult.error("", TeamErrorCode.NOT_FOUND, "Team not found");
            }
            TeamDefinition definition = runtime.get().getDefinition();
            if (!query.getOwnerChatterId().equals(definition.getOwnerChatterId())) {
                return TeamCommandResult.error("", TeamErrorCode.UNAUTHORIZED,
                        "Team does not belong to ownerChatterId");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("team", definition);
            data.put("latestEventSeq", runtime.get().getLatestEventSeq());
            return TeamCommandResult.success("", "OK", "Team loaded",
                    definition.getVersion(), data);
        } catch (IllegalArgumentException e) {
            return TeamCommandResult.error("", TeamErrorCode.VALIDATION_ERROR, e.getMessage());
        }
    }

    public TeamClientRegistry getClientRegistry() {
        return clientRegistry;
    }

    /**
     * 获取当前 Team 唯一的 talkTo dispatcher。dispatcher 共享 member inbox，
     * 但路由键始终是 teamId + teamMemberId，不进入普通 robot registry。
     */
    public TeamTalkToDispatcher getOrCreateTalkToDispatcher(String teamId) {
        ensureOpen();
        TeamRuntime runtime = teams.get(teamId);
        if (runtime == null) {
            throw new IllegalArgumentException("Team runtime not found");
        }
        return talkToDispatchers.computeIfAbsent(teamId,
                ignored -> new TeamTalkToDispatcher(
                        runtime, clientRegistry, eventSink, this::onMemberState));
    }

    /**
     * Team 删除/失败回收的显式扩展点；FT-CMD-501 的 delete 状态机将调用同一入口。
     */
    public void cleanupTalkTo(String teamId) {
        TeamTalkToDispatcher dispatcher = talkToDispatchers.remove(teamId);
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    public void setScheduleCleanup(Consumer<String> cleanup) {
        this.scheduleCleanup = cleanup == null ? ignored -> { } : cleanup;
    }

    public void setScheduleOrphanCleanup(
            Consumer<Set<String>> cleanup, IntSupplier ownerCount) {
        setScheduleOrphanCleanup(cleanup, ownerCount, ignored -> 0);
    }

    public void setScheduleOrphanCleanup(
            Consumer<Set<String>> cleanup, IntSupplier ownerCount,
            ToIntFunction<String> teamOwnerCount) {
        this.scheduleOrphanCleanup =
                cleanup == null ? ignored -> { } : cleanup;
        this.scheduleOwnerCount =
                ownerCount == null ? () -> 0 : ownerCount;
        this.scheduleTeamOwnerCount =
                teamOwnerCount == null ? ignored -> 0 : teamOwnerCount;
    }

    /** Team 复用来源 robot MemoryManager 的手动 dream 路由。 */
    public void setMemoryDreamTrigger(BiPredicate<String, String> trigger) {
        this.memoryDreamTrigger = trigger == null
                ? (sourceGroupId, workspacePath) -> false : trigger;
    }

    /**
     * delete 状态机的 schedule/talkTo 统一清理入口。
     * 普通 shutdown 不调用，避免误删需在重启后恢复的持久任务。
     */
    public void cleanupEphemeralResourcesForDelete(String teamId) {
        cleanupTalkTo(teamId);
        scheduleCleanup.accept(teamId);
    }

    /**
     * 执行 Team owner 的定时任务：只替换目标 member session，完整重跑
     * initializer 后以 schedule execution 选项发送，避免任务递归创建任务。
     */
    public boolean executeScheduledPrompt(ScheduleOwnerKey owner, String taskId,
                                          String prompt) {
        if (owner == null || !owner.isTeam() || prompt == null
                || prompt.trim().isEmpty() || closed.get()
                || startupCoordinator == null) {
            return false;
        }
        TeamRuntime runtime = teams.get(owner.getTeamId());
        if (runtime == null) {
            return false;
        }
        runtime.getOperationLock().lock();
        try {
            TeamDefinition team = runtime.getDefinition();
            if (!runtime.isAcceptingRequests()
                    || team.getState()
                    != com.mola.cmd.proxy.app.acp.team.model.TeamState.READY
                    || !team.getOwnerChatterId().equals(owner.getOwnerId())) {
                return false;
            }
            TeamMemberDefinition member =
                    findMember(team, owner.getTeamMemberId());
            if (member == null
                    || member.getState()
                    != com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.READY) {
                return false;
            }
            AcpClient old = clientRegistry.get(
                    team.getTeamId(), member.getTeamMemberId()).orElse(null);
            if (old == null || old.getState() != AbstractAcpClient.State.READY) {
                return false;
            }

            publishMemberState(runtime, member.getTeamMemberId(),
                    com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.STARTING, null);
            clientRegistry.remove(team.getTeamId(), member.getTeamMemberId());
            try {
                old.close();
            } catch (IOException ignored) {
            }

            TeamMemberDefinition starting = findMember(
                    runtime.getDefinition(), member.getTeamMemberId());
            AcpClient replacement = startupCoordinator.replaceMember(
                    runtime, starting, TeamMemberStartOptions.newSession());
            if (!clientRegistry.register(
                    team.getTeamId(), member.getTeamMemberId(), replacement)) {
                replacement.close();
                throw new IOException("duplicate Team schedule replacement client");
            }
            publishMemberState(runtime, member.getTeamMemberId(),
                    com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.READY, null);
            publishMemberState(runtime, member.getTeamMemberId(),
                    com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.BUSY, null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("eventType", "SCHEDULE_TRIGGER");
            data.put("taskId", taskId);
            data.put("owner", owner);
            data.put("sessionId", replacement.getSessionId());
            eventSink.publish(TeamEventEnvelope.next(runtime,
                    member.getTeamMemberId(), member.getAcpClientId(),
                    TeamEventType.SCHEDULE_EVENT, data));
            replacement.send(prompt, null, PromptOptions.forScheduleExecution());
            return true;
        } catch (Exception error) {
            TeamError teamError = TeamError.of(
                    TeamErrorCode.CLIENT_START_FAILED, safeMessage(error), true);
            try {
                publishMemberState(runtime, owner.getTeamMemberId(),
                        com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.ERROR,
                        teamError);
            } catch (Exception ignored) {
            }
            return false;
        } finally {
            runtime.getOperationLock().unlock();
        }
    }

    public TeamCommandResult send(String requestId, TeamMemberCommand command) {
        MemberRoute route;
        try {
            route = requireMemberRoute(command, true);
            requireText(command.getMessage(), "message");
            validateFiles(command.getFiles());
        } catch (IllegalArgumentException e) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.VALIDATION_ERROR, e.getMessage());
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
        route.runtime.getOperationLock().lock();
        try {
            AcpClient client = requireReadyClient(route);
            publishMemberState(route.runtime, route.member.getTeamMemberId(),
                    com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.BUSY, null);
            client.send(command.getMessage(), command.getFiles());
            TeamMemberDefinition busy = findMember(
                    route.runtime.getDefinition(), route.member.getTeamMemberId());
            Map<String, Object> data = memberData(route.runtime, busy, client);
            data.put("queued", true);
            return TeamCommandResult.success(requestId, "QUEUED",
                    "Team message queued", route.runtime.getDefinition().getVersion(), data);
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        } catch (IOException e) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.INTERNAL_ERROR, safeMessage(e));
        } finally {
            route.runtime.getOperationLock().unlock();
        }
    }

    public TeamCommandResult cancel(String requestId, TeamMemberCommand command) {
        MemberRoute route;
        try {
            route = requireMemberRoute(command, false);
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
        route.runtime.getOperationLock().lock();
        try {
            // 与 delete/session replace 使用同一屏障；锁内重查，避免检查后进入 DELETING。
            route = requireMemberRoute(command, false);
            AcpClient client = requireCancellableClient(route);
            client.cancel();
            return TeamCommandResult.success(requestId, "OK",
                    "Team prompt cancellation sent", route.runtime.getDefinition().getVersion(),
                    memberData(route.runtime, route.member, client));
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        } catch (Exception e) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.INTERNAL_ERROR, safeMessage(e));
        } finally {
            route.runtime.getOperationLock().unlock();
        }
    }

    /** 手动触发与来源普通 robot 完全相同的 Memory Dream。 */
    public TeamCommandResult memoryDream(String requestId, TeamMemberCommand command) {
        MemberRoute route;
        try {
            route = requireMemberRoute(command, true);
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
        route.runtime.getOperationLock().lock();
        try {
            AcpClient client = requireReadyClient(route);
            boolean triggered = memoryDreamTrigger.test(
                    route.member.getSourceGroupId(), client.getWorkspacePath());
            if (!triggered) {
                return TeamCommandResult.error(requestId,
                        TeamErrorCode.MEMORY_NOT_ENABLED,
                        "Source robot does not have writable memory enabled");
            }
            Map<String, Object> data = memberData(
                    route.runtime, route.member, client);
            data.put("triggered", true);
            data.put("memoryOwnerSourceGroupId", route.member.getSourceGroupId());
            return TeamCommandResult.success(requestId, "OK",
                    "Team member memory dream triggered",
                    route.runtime.getDefinition().getVersion(), data);
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        } catch (RuntimeException e) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.INTERNAL_ERROR, safeMessage(e));
        } finally {
            route.runtime.getOperationLock().unlock();
        }
    }

    public TeamCommandResult listSessions(String requestId, TeamMemberCommand command) {
        try {
            MemberRoute route = requireMemberRoute(command, false);
            AcpClient client = requireExistingClient(route);
            int limit = command.getLimit() == null ? 7 : command.getLimit();
            if (limit < 1 || limit > 50) {
                throw new IllegalArgumentException("limit must be between 1 and 50");
            }
            List<Map<String, Object>> sessions = new ArrayList<>();
            for (com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager.SessionSummary
                    summary : client.getHistoryManager().listRecentSessions(limit)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sessionId", summary.getSessionId());
                item.put("preview", summary.getPreview());
                item.put("lastModified", summary.getLastModified());
                item.put("current", summary.getSessionId().equals(client.getSessionId()));
                sessions.add(item);
            }
            Map<String, Object> data = memberData(route.runtime, route.member, client);
            data.put("sessions", sessions);
            return TeamCommandResult.success(requestId, "OK",
                    "Team sessions listed", route.runtime.getDefinition().getVersion(), data);
        } catch (IllegalArgumentException e) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.VALIDATION_ERROR, e.getMessage());
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
    }

    public TeamCommandResult getStatus(String requestId, TeamMemberCommand command) {
        try {
            MemberRoute route = requireMemberRoute(command, false);
            AcpClient client = clientRegistry.get(route.team.getTeamId(),
                    route.member.getTeamMemberId()).orElse(null);
            return TeamCommandResult.success(requestId, "OK",
                    "Team member status loaded", route.team.getVersion(),
                    memberData(route.runtime, route.member, client));
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
    }

    public TeamCommandResult getContextUsage(String requestId,
                                             TeamMemberCommand command) {
        try {
            MemberRoute route = requireMemberRoute(command, false);
            AcpClient client = requireExistingClient(route);
            Map<String, Object> data = memberData(route.runtime, route.member, client);
            data.put("contextUsagePercentage", client.getContextUsagePercentage());
            return TeamCommandResult.success(requestId, "OK",
                    "Team context usage loaded", route.team.getVersion(), data);
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
    }

    public TeamCommandResult newSession(String requestId, TeamMemberCommand command) {
        return replaceSession(requestId, command, TeamMemberStartOptions.newSession());
    }

    public TeamCommandResult restoreSession(String requestId, TeamMemberCommand command) {
        try {
            return replaceSession(requestId, command,
                    TeamMemberStartOptions.restore(
                            command == null ? null : command.getSessionId()));
        } catch (IllegalArgumentException e) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.VALIDATION_ERROR, e.getMessage());
        }
    }

    public void onMemberState(String teamId, String memberId,
                              com.mola.cmd.proxy.app.acp.team.model.TeamMemberState state,
                              TeamError error) {
        TeamRuntime runtime = teams.get(teamId);
        if (runtime == null || closed.get()) {
            return;
        }
        runtime.getOperationLock().lock();
        try {
            if (runtime.getDefinition().getState()
                    != com.mola.cmd.proxy.app.acp.team.model.TeamState.READY) {
                return;
            }
            publishMemberState(runtime, memberId, state, error);
        } catch (Exception ignored) {
        } finally {
            runtime.getOperationLock().unlock();
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    public TeamResourceSnapshot resourceSnapshot() {
        return new TeamResourceSnapshot(
                teams.size(), clientRegistry.size(),
                talkToDispatchers.size(), pendingCleanup.size(),
                scheduleOwnerCount.getAsInt());
    }

    public TeamMetricsSnapshot metricsSnapshot() {
        return metrics.snapshot(activeTeamCount(), activeMemberCount(),
                resourceSnapshot());
    }

    public TeamLimits getLimits() {
        return limits;
    }

    public TeamResourceSnapshot resourceSnapshot(String teamId) {
        return new TeamResourceSnapshot(
                teams.containsKey(teamId) ? 1 : 0,
                clientRegistry.size(teamId),
                talkToDispatchers.containsKey(teamId) ? 1 : 0,
                pendingCleanup.containsKey(teamId) ? 1 : 0,
                scheduleTeamOwnerCount.applyAsInt(teamId));
    }

    void reapResources(long now) {
        metrics.reaperRun();
        for (Map.Entry<String, Future<List<IOException>>> entry
                : pendingCleanup.entrySet()) {
            if (entry.getValue().isDone()) {
                pendingCleanup.remove(entry.getKey(), entry.getValue());
            }
        }
        Set<String> activeIds = new HashSet<>(teams.keySet());
        for (String orphanTeamId : clientRegistry.teamIds()) {
            if (!activeIds.contains(orphanTeamId)) {
                clientRegistry.closeTeam(orphanTeamId);
            }
        }
        for (TeamRuntime runtime : new ArrayList<>(teams.values())) {
            TeamState state = runtime.getDefinition().getState();
            if (state == TeamState.DELETING) {
                resumeDeleting(runtime);
            } else if (state.isTerminal()) {
                cleanupEphemeralResourcesForDelete(
                        runtime.getDefinition().getTeamId());
                clientRegistry.closeTeam(runtime.getDefinition().getTeamId());
                teams.remove(runtime.getDefinition().getTeamId(), runtime);
                try {
                    store.deleteTeam(runtime.getDefinition().getTeamId());
                } catch (IOException ignored) {
                }
            }
        }
        scheduleOrphanCleanup.accept(
                Collections.unmodifiableSet(new HashSet<>(teams.keySet())));
        try {
            for (TeamOperationRecord operation : store.loadOperations()) {
                if (operation.getExpiresAt() <= now) {
                    store.deleteOperation(operation.getRequestId());
                }
            }
            for (TeamTombstone tombstone : store.loadTombstones()) {
                historyArchiver.deleteExpired(tombstone.getTeamId(), now);
                if (tombstone.getExpireAt() <= now) {
                    scheduleCleanup.accept(tombstone.getTeamId());
                    store.deleteTombstone(tombstone.getTeamId());
                }
            }
        } catch (Exception ignored) {
            // reaper 是最终一致性兜底；单次失败留待下一轮。
        }
        TeamMetricsSnapshot snapshot = metricsSnapshot();
        logger.debug("team_metrics activeTeams={} activeMembers={} clients={}"
                        + " talkToDispatchers={} pendingCleanup={}"
                        + " scheduleOwners={} reaperRuns={}",
                snapshot.getActiveTeams(), snapshot.getActiveMembers(),
                snapshot.getResources().getClients(),
                snapshot.getResources().getTalkToDispatchers(),
                snapshot.getResources().getPendingCleanup(),
                snapshot.getResources().getScheduleOwners(),
                snapshot.getReaperRuns());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (TeamRuntime runtime : teams.values()) {
            runtime.stopAcceptingRequests();
        }
        resourceReaper.close();
        for (TeamTalkToDispatcher dispatcher : talkToDispatchers.values()) {
            dispatcher.close();
        }
        talkToDispatchers.clear();
        clientRegistry.closeAll();
        if (startupCoordinator != null) {
            startupCoordinator.close();
        }
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(
                    cleanupTimeoutMillis, TimeUnit.MILLISECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        pendingCleanup.clear();
        teams.clear();
    }

    private TeamCommandResult finalizeDelete(
            TeamRuntime runtime, String requestId) throws IOException {
        TeamDefinition deleting = runtime.getDefinition();
        if (deleting.getState() != TeamState.DELETING) {
            throw new IOException("Team is not DELETING");
        }
        List<TeamError> warnings = new ArrayList<>();
        cleanupEphemeralResourcesForDelete(deleting.getTeamId());

        Future<List<IOException>> closeFuture = cleanupExecutor.submit(
                () -> clientRegistry.closeTeam(deleting.getTeamId()));
        try {
            List<IOException> closeErrors =
                    closeFuture.get(cleanupTimeoutMillis, TimeUnit.MILLISECONDS);
            for (IOException closeError : closeErrors) {
                warnings.add(cleanupWarning(
                        "client-close", safeMessage(closeError)));
            }
        } catch (TimeoutException e) {
            pendingCleanup.put(deleting.getTeamId(), closeFuture);
            warnings.add(cleanupWarning("client-close",
                    "Team client cleanup timed out after "
                            + cleanupTimeoutMillis + "ms; reaper will continue"));
        } catch (Exception e) {
            warnings.add(cleanupWarning("client-close", safeMessage(e)));
        }

        long deletedAt = System.currentTimeMillis();
        long archiveExpireAt = deletedAt + TOMBSTONE_TTL_MILLIS;
        try {
            historyArchiver.archive(deleting.getTeamId(), archiveExpireAt);
        } catch (Exception e) {
            warnings.add(cleanupWarning("history-archive", safeMessage(e)));
        }

        TeamState finalState = warnings.isEmpty()
                ? TeamState.DELETED : TeamState.DELETED_WITH_WARNINGS;
        TeamError lastError = warnings.isEmpty() ? null : warnings.get(0);
        TeamDefinition terminal = deleting.transitionTo(
                finalState, lastError, deletedAt);
        store.saveTeam(terminal);
        if (!runtime.publishNextDefinition(terminal)) {
            throw new IOException("Team version changed before terminal publish");
        }
        store.saveTombstone(new TeamTombstone(
                terminal.getTeamId(), requestId, finalState,
                deletedAt, archiveExpireAt, warnings));

        teams.remove(terminal.getTeamId(), runtime);
        Map<String, Object> data = deleteData(
                terminal, warnings, archiveExpireAt);
        publishEvent(runtime, TeamEventType.TEAM_DELETED, data);
        try {
            store.deleteTeam(terminal.getTeamId());
        } catch (IOException e) {
            // Tombstone 已经落盘，定义文件残留由 reaper/下次恢复忽略并再次清理。
        }
        metrics.deleteCompleted(!warnings.isEmpty());
        logger.info("team_lifecycle action=delete_completed teamId={}"
                        + " requestId={} state={} version={} warnings={}"
                        + " resourcesZero={}",
                terminal.getTeamId(), requestId, finalState,
                terminal.getVersion(), warnings.size(),
                resourceSnapshot(terminal.getTeamId()).isZero());
        return TeamCommandResult.success(
                requestId, finalState.name(),
                warnings.isEmpty() ? "Team deleted"
                        : "Team deleted with cleanup warnings",
                terminal.getVersion(), data);
    }

    private void resumeDeleting(TeamRuntime runtime) {
        if (closed.get()) return;
        runtime.getOperationLock().lock();
        try {
            TeamDefinition definition = runtime.getDefinition();
            if (definition.getState() != TeamState.DELETING) return;
            String requestId = definition.getDeleteRequestId();
            if (requestId == null || requestId.trim().isEmpty()) {
                requestId = "recovery-" + definition.getTeamId();
            }
            TeamCommandResult result = finalizeDelete(runtime, requestId);
            Optional<TeamOperationRecord> operation =
                    store.loadOperation(requestId);
            if (operation.isPresent()) {
                TeamOperationRecord existing = operation.get();
                store.saveOperation(new TeamOperationRecord(
                        requestId, TeamOperationRecord.Operation.DELETE,
                        existing.getPayloadHash(), definition.getTeamId(),
                        TeamOperationRecord.Status.SUCCEEDED,
                        gson.toJson(result), existing.getCreatedAt(),
                        existing.getExpiresAt()));
            }
        } catch (Exception e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("team", runtime.getDefinition());
            data.put("error", TeamError.of(
                    TeamErrorCode.INTERNAL_ERROR, safeMessage(e), true));
            publishEvent(runtime, TeamEventType.TEAM_DELETE_FAILED, data);
        } finally {
            runtime.getOperationLock().unlock();
        }
    }

    private TeamCommandResult deletedResult(
            String requestId, TeamDefinition terminal,
            List<TeamError> warnings, long archiveExpireAt) {
        return TeamCommandResult.success(
                requestId, terminal.getState().name(),
                "Team already deleted", terminal.getVersion(),
                deleteData(terminal, warnings, archiveExpireAt));
    }

    private Map<String, Object> deleteData(
            TeamDefinition team, List<TeamError> warnings,
            long archiveExpireAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("team", team);
        data.put("warnings", warnings);
        data.put("archiveExpireAt", archiveExpireAt);
        data.put("resources", resourceSnapshot(team.getTeamId()));
        return data;
    }

    private static TeamError cleanupWarning(String phase, String message) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("phase", phase);
        return new TeamError(TeamErrorCode.INTERNAL_ERROR,
                message, true, details, System.currentTimeMillis());
    }

    private void publishEvent(TeamRuntime runtime, TeamEventType type,
                              Object data) {
        try {
            eventSink.publish(TeamEventEnvelope.next(
                    runtime, null, null, type, data));
        } catch (RuntimeException ignored) {
            // 事件是可重建投影，不回滚权威持久状态。
        }
    }

    private void publishRecoveryEvent(
            TeamRuntime runtime, TeamEventType type, TeamError error) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("team", runtime.getDefinition());
        data.put("members", runtime.getDefinition().getMembers());
        if (error != null) data.put("error", error);
        publishEvent(runtime, type, data);
    }

    private void startMembers(TeamRuntime runtime) {
        if (startupCoordinator == null || closed.get()) {
            return;
        }
        startupCoordinator.startAsync(runtime)
                .whenComplete((result, error) -> completeStartup(runtime, result, error));
    }

    private void completeStartup(TeamRuntime runtime, TeamStartupCoordinator.Result result,
                                 Throwable failure) {
        runtime.getOperationLock().lock();
        try {
            TeamState startingState = runtime.getDefinition().getState();
            if (closed.get()
                    || (startingState != TeamState.CREATING
                    && startingState != TeamState.RECOVERING)) {
                clientRegistry.closeTeam(runtime.getDefinition().getTeamId());
                return;
            }
            TeamError teamError;
            if (failure == null && result != null) {
                teamError = result.getError();
            } else {
                teamError = TeamError.of(TeamErrorCode.CLIENT_START_FAILED,
                        failure == null ? "Team startup returned no result"
                                : safeMessage(failure), true);
            }
            boolean successful =
                    failure == null && result != null && result.isSuccessful();
            List<TeamMemberDefinition> members = result == null
                    ? markAllFailed(runtime.getDefinition().getMembers(), teamError)
                    : result.getMembers();
            TeamDefinition next = runtime.getDefinition().transitionWithMembers(
                    successful
                            ? com.mola.cmd.proxy.app.acp.team.model.TeamState.READY
                            : com.mola.cmd.proxy.app.acp.team.model.TeamState.FAILED,
                    members, teamError, System.currentTimeMillis());
            store.saveTeam(next);
            if (!runtime.publishNextDefinition(next)) {
                clientRegistry.closeTeam(next.getTeamId());
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("team", next);
            data.put("members", next.getMembers());
            if (teamError != null) {
                data.put("error", teamError);
            }
            try {
                eventSink.publish(TeamEventEnvelope.next(runtime, null, null,
                        startingState == TeamState.RECOVERING
                                ? (successful ? TeamEventType.TEAM_RECOVERED
                                : TeamEventType.TEAM_RECOVERY_FAILED)
                                : (successful ? TeamEventType.TEAM_READY
                                : TeamEventType.TEAM_CREATE_FAILED), data));
            } catch (RuntimeException ignored) {
                // callback 只是可重建投影，失败不得改变已持久化的权威状态或回滚 client。
            }
            if (!successful) {
                cleanupTalkTo(next.getTeamId());
                runtime.stopAcceptingRequests();
            }
        } catch (Exception e) {
            cleanupTalkTo(runtime.getDefinition().getTeamId());
            clientRegistry.closeTeam(runtime.getDefinition().getTeamId());
            runtime.stopAcceptingRequests();
        } finally {
            runtime.getOperationLock().unlock();
        }
    }

    private static List<TeamMemberDefinition> markAllFailed(
            List<TeamMemberDefinition> members, TeamError error) {
        TeamError resolved = error == null
                ? TeamError.of(TeamErrorCode.CLIENT_START_FAILED,
                "Team startup failed", true) : error;
        List<TeamMemberDefinition> failed = new ArrayList<>();
        for (TeamMemberDefinition member : members) {
            failed.add(member.withState(
                    com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.ERROR,
                    null, resolved));
        }
        return failed;
    }

    private TeamCommandResult replaceSession(String requestId, TeamMemberCommand command,
                                             TeamMemberStartOptions options) {
        MemberRoute route;
        try {
            route = requireMemberRoute(command, true);
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
        if (startupCoordinator == null) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.TEAM_NOT_READY, "Team startup coordinator is unavailable");
        }
        route.runtime.getOperationLock().lock();
        try {
            AcpClient old = requireReadyClient(route);
            publishMemberState(route.runtime, route.member.getTeamMemberId(),
                    com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.STARTING, null);
            clientRegistry.remove(route.team.getTeamId(), route.member.getTeamMemberId());
            try {
                old.close();
            } catch (IOException ignored) {
            }
            TeamMemberDefinition starting = findMember(
                    route.runtime.getDefinition(), route.member.getTeamMemberId());
            AcpClient replacement = startupCoordinator.replaceMember(
                    route.runtime, starting, options);
            if (!clientRegistry.register(route.team.getTeamId(),
                    route.member.getTeamMemberId(), replacement)) {
                replacement.close();
                throw new IllegalStateException("duplicate Team member client");
            }
            publishMemberState(route.runtime, route.member.getTeamMemberId(),
                    com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.READY, null);
            TeamMemberDefinition ready = findMember(
                    route.runtime.getDefinition(), route.member.getTeamMemberId());
            Map<String, Object> data = memberData(route.runtime, ready, replacement);
            data.put("restored", options.getTargetRestoreSessionId() != null);
            return TeamCommandResult.success(requestId, "OK",
                    options.getTargetRestoreSessionId() == null
                            ? "Team member session created"
                            : "Team member session restored",
                    route.runtime.getDefinition().getVersion(), data);
        } catch (Exception e) {
            TeamError error = TeamError.of(TeamErrorCode.CLIENT_START_FAILED,
                    safeMessage(e), true);
            try {
                publishMemberState(route.runtime, route.member.getTeamMemberId(),
                        com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.ERROR, error);
            } catch (Exception ignored) {
            }
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.CLIENT_START_FAILED, safeMessage(e));
        } finally {
            route.runtime.getOperationLock().unlock();
        }
    }

    private MemberRoute requireMemberRoute(TeamMemberCommand command, boolean requireReady)
            throws MemberRouteException {
        if (command == null) {
            throw new MemberRouteException(
                    TeamErrorCode.VALIDATION_ERROR, "payload is required");
        }
        try {
            requireSchema(command.getSchemaVersion());
            requireText(command.getOwnerChatterId(), "ownerChatterId");
            requireSafeId(command.getTeamId(), "teamId");
            requireSafeId(command.getTeamMemberId(), "teamMemberId");
        } catch (IllegalArgumentException e) {
            throw new MemberRouteException(TeamErrorCode.VALIDATION_ERROR, e.getMessage());
        }
        TeamRuntime runtime = teams.get(command.getTeamId());
        if (runtime == null) {
            throw new MemberRouteException(TeamErrorCode.NOT_FOUND, "Team not found");
        }
        TeamDefinition team = runtime.getDefinition();
        if (!command.getOwnerChatterId().equals(team.getOwnerChatterId())) {
            throw new MemberRouteException(
                    TeamErrorCode.UNAUTHORIZED, "Team does not belong to ownerChatterId");
        }
        if (team.getState() == TeamState.DELETING) {
            throw new MemberRouteException(
                    TeamErrorCode.TEAM_DELETING, "Team is DELETING");
        }
        TeamMemberDefinition member = findMember(team, command.getTeamMemberId());
        if (member == null) {
            throw new MemberRouteException(
                    TeamErrorCode.NOT_FOUND, "Team member not found");
        }
        if (command.getAcpClientId() != null
                && !command.getAcpClientId().trim().isEmpty()
                && !member.getAcpClientId().equals(command.getAcpClientId())) {
            throw new MemberRouteException(
                    TeamErrorCode.VALIDATION_ERROR, "acpClientId does not match teamMemberId");
        }
        if (requireReady && team.getState()
                != com.mola.cmd.proxy.app.acp.team.model.TeamState.READY) {
            throw new MemberRouteException(
                    TeamErrorCode.TEAM_NOT_READY, "Team is not READY");
        }
        return new MemberRoute(runtime, team, member);
    }

    private AcpClient requireExistingClient(MemberRoute route)
            throws MemberRouteException {
        return clientRegistry.get(route.team.getTeamId(), route.member.getTeamMemberId())
                .orElseThrow(() -> new MemberRouteException(
                        TeamErrorCode.CLIENT_CLOSED,
                        "Team member client is not available"));
    }

    private AcpClient requireReadyClient(MemberRoute route)
            throws MemberRouteException {
        AcpClient client = requireExistingClient(route);
        if (route.member.getState()
                != com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.READY
                || client.getState() != AbstractAcpClient.State.READY) {
            throw new MemberRouteException(
                    TeamErrorCode.MEMBER_BUSY, "Team member is not READY");
        }
        return client;
    }

    /** cancel 允许 READY/BUSY，其他成员或 client 生命周期状态一律拒绝。 */
    private AcpClient requireCancellableClient(MemberRoute route)
            throws MemberRouteException {
        if (route.team.getState() != TeamState.READY) {
            throw new MemberRouteException(
                    TeamErrorCode.TEAM_NOT_READY, "Team is not READY");
        }
        com.mola.cmd.proxy.app.acp.team.model.TeamMemberState memberState =
                route.member.getState();
        if (memberState != com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.READY
                && memberState != com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.BUSY) {
            TeamErrorCode code = memberState
                    == com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.STARTING
                    ? TeamErrorCode.TEAM_NOT_READY : TeamErrorCode.CLIENT_CLOSED;
            throw new MemberRouteException(code,
                    "Team member cannot be cancelled in state " + memberState.name());
        }
        AcpClient client = requireExistingClient(route);
        AbstractAcpClient.State clientState = client.getState();
        if (clientState != AbstractAcpClient.State.READY
                && clientState != AbstractAcpClient.State.BUSY) {
            TeamErrorCode code = clientState == AbstractAcpClient.State.CREATED
                    || clientState == AbstractAcpClient.State.STARTING
                    ? TeamErrorCode.TEAM_NOT_READY : TeamErrorCode.CLIENT_CLOSED;
            throw new MemberRouteException(code,
                    "Team member client cannot be cancelled in state "
                            + clientState.name());
        }
        return client;
    }

    private void publishMemberState(
            TeamRuntime runtime, String memberId,
            com.mola.cmd.proxy.app.acp.team.model.TeamMemberState state,
            TeamError error) throws IOException {
        TeamDefinition current = runtime.getDefinition();
        List<TeamMemberDefinition> members = new ArrayList<>();
        TeamMemberDefinition updated = null;
        for (TeamMemberDefinition member : current.getMembers()) {
            if (member.getTeamMemberId().equals(memberId)) {
                String sessionId = member.getSessionId();
                AcpClient client = clientRegistry.get(
                        current.getTeamId(), memberId).orElse(null);
                if (client != null && client.getSessionId() != null) {
                    sessionId = client.getSessionId();
                }
                updated = member.withState(state, sessionId, error);
                members.add(updated);
            } else {
                members.add(member);
            }
        }
        if (updated == null) {
            throw new IllegalArgumentException("Team member not found");
        }
        TeamDefinition next = current.withMembers(members, System.currentTimeMillis());
        store.saveTeam(next);
        if (!runtime.publishNextDefinition(next)) {
            throw new IOException("Team runtime version changed while publishing member state");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("team", next);
        data.put("member", updated);
        data.put("state", state.name());
        if (error != null) {
            data.put("error", error);
        }
        try {
            eventSink.publish(TeamEventEnvelope.next(runtime,
                    updated.getTeamMemberId(), updated.getAcpClientId(),
                    TeamEventType.MEMBER_STATE_CHANGED, data));
        } catch (RuntimeException ignored) {
        }
    }

    private static TeamMemberDefinition findMember(TeamDefinition definition,
                                                   String memberId) {
        for (TeamMemberDefinition member : definition.getMembers()) {
            if (member.getTeamMemberId().equals(memberId)) {
                return member;
            }
        }
        return null;
    }

    private static Map<String, Object> memberData(
            TeamRuntime runtime, TeamMemberDefinition member, AcpClient client) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("teamId", runtime.getDefinition().getTeamId());
        data.put("teamState", runtime.getDefinition().getState().name());
        data.put("teamMemberId", member.getTeamMemberId());
        data.put("acpClientId", member.getAcpClientId());
        data.put("memberState", member.getState().name());
        data.put("clientState", client == null ? "CLOSED" : client.getState().name());
        data.put("sessionId", client == null ? member.getSessionId() : client.getSessionId());
        return data;
    }

    private static void validateFiles(List<Map<String, String>> files) {
        if (files == null) {
            return;
        }
        if (files.size() > 10) {
            throw new IllegalArgumentException("files size must not exceed 10");
        }
        for (Map<String, String> file : files) {
            if (file == null || file.size() != 1) {
                throw new IllegalArgumentException(
                        "each files item must contain exactly one filename-to-content entry");
            }
            Map.Entry<String, String> entry = file.entrySet().iterator().next();
            String name = requireText(entry.getKey(), "file name");
            if (name.contains("/") || name.contains("\\") || ".".equals(name)
                    || "..".equals(name)) {
                throw new IllegalArgumentException("file name must be a basename");
            }
            String content = requireText(entry.getValue(), "file content");
            if (!content.startsWith("http://") && !content.startsWith("https://")) {
                try {
                    Base64.getDecoder().decode(content);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "file content must be Base64 or an HTTP(S) URL");
                }
            }
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static final class MemberRoute {
        private final TeamRuntime runtime;
        private final TeamDefinition team;
        private final TeamMemberDefinition member;

        private MemberRoute(TeamRuntime runtime, TeamDefinition team,
                            TeamMemberDefinition member) {
            this.runtime = runtime;
            this.team = team;
            this.member = member;
        }
    }

    private static final class MemberRouteException extends Exception {
        private final TeamErrorCode code;

        private MemberRouteException(TeamErrorCode code, String message) {
            super(message);
            this.code = code;
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("TeamManager is closed");
        }
    }

    private TeamCommandResult finishOperation(TeamCreateCommand command, String payloadHash,
                                              TeamCommandResult result,
                                              TeamOperationRecord.Status status)
            throws IOException {
        store.saveOperation(new TeamOperationRecord(
                command.getRequestId(), TeamOperationRecord.Operation.CREATE,
                payloadHash, command.getTeamId(), status, gson.toJson(result),
                System.currentTimeMillis(),
                System.currentTimeMillis() + 24L * 60L * 60L * 1000L));
        return result;
    }

    private TeamCommandResult finishDeleteOperation(
            TeamDeleteCommand command, String payloadHash,
            TeamCommandResult result, TeamOperationRecord.Status status)
            throws IOException {
        store.saveOperation(new TeamOperationRecord(
                command.getRequestId(), TeamOperationRecord.Operation.DELETE,
                payloadHash, command.getTeamId(), status, gson.toJson(result),
                System.currentTimeMillis(),
                System.currentTimeMillis() + OPERATION_TTL_MILLIS));
        return result;
    }

    private TeamCommandResult finishOperationQuietly(TeamCreateCommand command,
                                                     String payloadHash,
                                                     TeamCommandResult result) {
        try {
            return finishOperation(command, payloadHash, result,
                    TeamOperationRecord.Status.FAILED);
        } catch (IOException ignored) {
            return result;
        }
    }

    private List<TeamMemberDefinition> resolveMembers(List<TeamMemberCreateSpec> specs)
            throws TeamSourceResolutionException {
        List<TeamMemberDefinition> result = new ArrayList<>();
        for (TeamMemberCreateSpec spec : specs) {
            TeamSourceRobotSnapshot snapshot = sourceResolver.snapshot(spec);
            AcpRobotParam robot = snapshot.copyRobotParam();
            String signature = robot.getSignature();
            String remark = signature == null || signature.trim().isEmpty()
                    ? "Team member based on " + robot.getName() : signature.trim();
            result.add(new TeamMemberDefinition(
                    spec.getTeamMemberId(), spec.getSourceRobotId(),
                    spec.getSourceGroupId(), robot.getName(), robot.getName(),
                    robot.getAvatar(), spec.getOrder(), remark,
                    snapshot.getConfigFingerprint()));
        }
        result.sort(Comparator.comparingInt(TeamMemberDefinition::getOrder));
        return result;
    }

    private TeamCommandResult quotaError(TeamCreateCommand command) {
        int requested = command.getMembers().size();
        if (requested > limits.getMaxMembersPerTeam()) {
            return TeamCommandResult.error(command.getRequestId(),
                    TeamErrorCode.QUOTA_EXCEEDED,
                    "Team members exceed maxMembersPerTeam="
                            + limits.getMaxMembersPerTeam());
        }
        if (activeTeamCount() >= limits.getMaxActiveTeams()) {
            return TeamCommandResult.error(command.getRequestId(),
                    TeamErrorCode.QUOTA_EXCEEDED,
                    "Active Teams exceed maxActiveTeams="
                            + limits.getMaxActiveTeams());
        }
        if (activeMemberCount() + requested > limits.getMaxTotalMembers()) {
            return TeamCommandResult.error(command.getRequestId(),
                    TeamErrorCode.QUOTA_EXCEEDED,
                    "Team members exceed maxTotalMembers="
                            + limits.getMaxTotalMembers());
        }
        return null;
    }

    private int activeTeamCount() {
        int count = 0;
        for (TeamRuntime runtime : teams.values()) {
            if (!runtime.getDefinition().getState().isTerminal()) count++;
        }
        return count;
    }

    private int activeMemberCount() {
        int count = 0;
        for (TeamRuntime runtime : teams.values()) {
            if (!runtime.getDefinition().getState().isTerminal()) {
                count += runtime.getDefinition().getMembers().size();
            }
        }
        return count;
    }

    private static void validateCreate(TeamCreateCommand command, String transportGroup) {
        if (command == null) {
            throw new IllegalArgumentException("create payload is required");
        }
        requireSchema(command.getSchemaVersion());
        requireSafeId(command.getRequestId(), "requestId");
        requireSafeId(command.getTeamId(), "teamId");
        requireText(command.getOwnerChatterId(), "ownerChatterId");
        String name = requireText(command.getName(), "name");
        if (name.length() > 40) {
            throw new IllegalArgumentException("name length must not exceed 40");
        }
        requireText(transportGroup, "transportGroup");
        List<TeamMemberCreateSpec> members = command.getMembers();
        if (members == null || members.size() < 2 || members.size() > 6) {
            throw new IllegalArgumentException("members size must be between 2 and 6");
        }
        Set<String> memberIds = new HashSet<>();
        Set<String> sourceGroups = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (TeamMemberCreateSpec member : members) {
            if (member == null) {
                throw new IllegalArgumentException("member must not be null");
            }
            requireSafeId(member.getTeamMemberId(), "teamMemberId");
            requireText(member.getSourceRobotId(), "sourceRobotId");
            requireText(member.getSourceGroupId(), "sourceGroupId");
            if (member.getOrder() < 0
                    || !memberIds.add(member.getTeamMemberId())
                    || !sourceGroups.add(member.getSourceGroupId())
                    || !orders.add(member.getOrder())) {
                throw new IllegalArgumentException(
                        "member ids, source groups and orders must be unique");
            }
        }
    }

    private static void validateDelete(TeamDeleteCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("delete payload is required");
        }
        requireSchema(command.getSchemaVersion());
        requireSafeId(command.getRequestId(), "requestId");
        requireSafeId(command.getTeamId(), "teamId");
        requireText(command.getOwnerChatterId(), "ownerChatterId");
        if (command.getExpectedVersion() != null
                && command.getExpectedVersion().longValue() < 1L) {
            throw new IllegalArgumentException(
                    "expectedVersion must be positive");
        }
    }

    private static void validateQuery(TeamQuery query, boolean teamRequired) {
        if (query == null) {
            throw new IllegalArgumentException("query payload is required");
        }
        requireSchema(query.getSchemaVersion());
        requireText(query.getOwnerChatterId(), "ownerChatterId");
        if (teamRequired) {
            requireSafeId(query.getTeamId(), "teamId");
        }
    }

    private static void requireSchema(String schemaVersion) {
        if (!TeamDefinition.SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported schemaVersion");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static void requireSafeId(String value, String field) {
        String text = requireText(value, field);
        if (!text.matches("[a-zA-Z0-9._-]+") || ".".equals(text) || "..".equals(text)) {
            throw new IllegalArgumentException(field + " is not safe");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                result.append(String.format("%02x", b & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
