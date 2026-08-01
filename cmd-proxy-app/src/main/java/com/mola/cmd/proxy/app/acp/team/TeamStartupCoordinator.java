package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamError;
import com.mola.cmd.proxy.app.acp.team.model.TeamErrorCode;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberState;
import com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Team member 的有界并行、全有或全无启动器。
 */
public final class TeamStartupCoordinator implements AutoCloseable {

    public static final class Result {
        private final boolean successful;
        private final List<TeamMemberDefinition> members;
        private final TeamError error;

        private Result(boolean successful, List<TeamMemberDefinition> members,
                       TeamError error) {
            this.successful = successful;
            this.members = Collections.unmodifiableList(new ArrayList<>(members));
            this.error = error;
        }

        public boolean isSuccessful() {
            return successful;
        }

        public List<TeamMemberDefinition> getMembers() {
            return members;
        }

        public TeamError getError() {
            return error;
        }
    }

    private static final class StartedMember {
        private final TeamMemberDefinition definition;
        private final AcpClient client;
        private final Exception error;

        private StartedMember(TeamMemberDefinition definition, AcpClient client,
                              Exception error) {
            this.definition = definition;
            this.client = client;
            this.error = error;
        }
    }

    private final TeamSourceRobotResolver sourceResolver;
    private final TeamMemberClientStarter starter;
    private final TeamClientRegistry registry;
    private final long timeoutMillis;
    private final ExecutorService memberExecutor;
    private final ExecutorService coordinatorExecutor;

    public TeamStartupCoordinator(TeamSourceRobotResolver sourceResolver,
                                  TeamMemberClientStarter starter,
                                  TeamClientRegistry registry,
                                  int maxParallel, long timeoutMillis) {
        this.sourceResolver = java.util.Objects.requireNonNull(
                sourceResolver, "sourceResolver");
        this.starter = java.util.Objects.requireNonNull(starter, "starter");
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        if (maxParallel < 1 || timeoutMillis < 1L) {
            throw new IllegalArgumentException("startup limits must be positive");
        }
        this.timeoutMillis = timeoutMillis;
        this.memberExecutor = Executors.newFixedThreadPool(maxParallel, runnable -> {
            Thread thread = new Thread(runnable, "team-member-start");
            thread.setDaemon(true);
            return thread;
        });
        this.coordinatorExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "team-start-coordinator");
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompletableFuture<Result> startAsync(TeamRuntime runtime) {
        return CompletableFuture.supplyAsync(() -> startBlocking(runtime),
                coordinatorExecutor);
    }

    private Result startBlocking(TeamRuntime runtime) {
        TeamDefinition team = runtime.getDefinition();
        List<StartupTask> tasks = new ArrayList<>();
        for (TeamMemberDefinition member : team.getMembers()) {
            tasks.add(new StartupTask(runtime, member));
        }

        List<StartedMember> started = new ArrayList<>();
        TeamError failure = null;
        try {
            List<Future<StartedMember>> futures =
                    memberExecutor.invokeAll(tasks, timeoutMillis, TimeUnit.MILLISECONDS);
            for (int i = 0; i < futures.size(); i++) {
                Future<StartedMember> future = futures.get(i);
                if (future.isCancelled()) {
                    closeQuietly(tasks.get(i).created.get());
                    started.add(new StartedMember(team.getMembers().get(i), null,
                            new IOException("Team member startup timed out")));
                    failure = TeamError.of(TeamErrorCode.CLIENT_START_FAILED,
                            "Team member startup timed out after " + timeoutMillis + "ms", true);
                    continue;
                }
                StartedMember result = future.get();
                started.add(result);
                if (result.error != null && failure == null) {
                    failure = toTeamError(result.error);
                }
            }
        } catch (Exception e) {
            failure = TeamError.of(TeamErrorCode.CLIENT_START_FAILED,
                    safeMessage(e), true);
        }

        if (failure == null) {
            try {
                if (!runtime.isAcceptingRequests()) {
                    throw new IllegalStateException(
                            "Team stopped accepting requests during startup");
                }
                for (StartedMember member : started) {
                    if (!registry.register(team.getTeamId(),
                            member.definition.getTeamMemberId(), member.client)) {
                        throw new IllegalStateException("duplicate Team member client");
                    }
                }
                List<TeamMemberDefinition> ready = new ArrayList<>();
                for (StartedMember member : started) {
                    ready.add(member.definition.withState(
                            TeamMemberState.READY, member.client.getSessionId(), null));
                }
                return new Result(true, ready, null);
            } catch (Exception e) {
                failure = TeamError.of(TeamErrorCode.CLIENT_START_FAILED,
                        safeMessage(e), true);
            }
        }

        registry.closeTeam(team.getTeamId());
        List<TeamMemberDefinition> failed = new ArrayList<>();
        for (StartedMember member : started) {
            closeQuietly(member.client);
            TeamError memberError = member.error == null ? failure
                    : toTeamError(member.error);
            TeamMemberState state = member.client == null
                    ? TeamMemberState.ERROR : TeamMemberState.CLOSED;
            failed.add(member.definition.withState(state, null, memberError));
        }
        while (failed.size() < team.getMembers().size()) {
            TeamMemberDefinition member = team.getMembers().get(failed.size());
            failed.add(member.withState(TeamMemberState.ERROR, null, failure));
        }
        return new Result(false, failed, failure);
    }

    public AcpClient replaceMember(TeamRuntime runtime, TeamMemberDefinition member,
                                   TeamMemberStartOptions options) throws Exception {
        AtomicReference<AcpClient> created = new AtomicReference<>();
        Future<StartedMember> future = memberExecutor.submit(
                () -> startReplacement(runtime, member, options, created));
        StartedMember started;
        try {
            started = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            closeQuietly(created.get());
            throw new IOException("Team member replacement timed out or failed", e);
        }
        if (started.error != null) {
            throw started.error;
        }
        return started.client;
    }

    private StartedMember startReplacement(TeamRuntime runtime,
                                           TeamMemberDefinition member,
                                           TeamMemberStartOptions options,
                                           AtomicReference<AcpClient> created) {
        try {
            TeamSourceRobotSnapshot snapshot = sourceResolver.restore(member);
            AcpClient client = starter.start(
                    runtime, member, snapshot, options, created::set);
            if (client == null) {
                throw new IllegalStateException("Team member starter returned null");
            }
            return new StartedMember(member, client, null);
        } catch (Exception e) {
            closeQuietly(created.get());
            return new StartedMember(member, null, e);
        }
    }

    private final class StartupTask implements Callable<StartedMember> {
        private final TeamRuntime runtime;
        private final TeamMemberDefinition member;
        private final AtomicReference<AcpClient> created = new AtomicReference<>();

        private StartupTask(TeamRuntime runtime, TeamMemberDefinition member) {
            this.runtime = runtime;
            this.member = member;
        }

        @Override
        public StartedMember call() {
            try {
                TeamSourceRobotSnapshot snapshot = sourceResolver.restore(member);
                AcpClient client = starter.start(runtime, member, snapshot,
                        TeamMemberStartOptions.initial(), created::set);
                if (client == null) {
                    throw new IllegalStateException("Team member starter returned null");
                }
                return new StartedMember(member, client, null);
            } catch (Exception e) {
                closeQuietly(created.get());
                return new StartedMember(member, null, e);
            }
        }
    }

    private static void closeQuietly(AcpClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (IOException ignored) {
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static TeamError toTeamError(Exception error) {
        TeamErrorCode code = error instanceof TeamSourceResolutionException
                ? ((TeamSourceResolutionException) error).getCode()
                : TeamErrorCode.CLIENT_START_FAILED;
        return TeamError.of(code, safeMessage(error), true);
    }

    @Override
    public void close() {
        coordinatorExecutor.shutdownNow();
        memberExecutor.shutdownNow();
    }
}
