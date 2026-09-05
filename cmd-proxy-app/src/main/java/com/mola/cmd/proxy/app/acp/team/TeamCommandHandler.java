package com.mola.cmd.proxy.app.acp.team;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mola.cmd.proxy.app.acp.team.model.TeamErrorCode;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamCommandResult;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamCreateCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamDeleteCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamQuery;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamMemberCommand;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamTalkToDeliverCommand;

import java.util.Map;

/**
 * CmdReceiver 的薄适配层。所有 Team command 均只接受一个 JSON cmdArg。
 */
public final class TeamCommandHandler {

    private final TeamManager manager;
    private final String transportGroup;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public TeamCommandHandler(TeamManager manager, String transportGroup) {
        this.manager = java.util.Objects.requireNonNull(manager, "manager");
        this.transportGroup = java.util.Objects.requireNonNull(
                transportGroup, "transportGroup");
    }

    public Map<String, String> handleCreate(String rpcRequestId, String[] args) {
        try {
            TeamCreateCommand command = parseSingleArg(args, TeamCreateCommand.class);
            return manager.create(command, transportGroup).toResultMap();
        } catch (IllegalArgumentException e) {
            return validationError(rpcRequestId, e).toResultMap();
        }
    }

    public Map<String, String> handleList(String rpcRequestId, String[] args) {
        try {
            TeamQuery query = parseSingleArg(args, TeamQuery.class);
            return manager.list(query).withRequestId(rpcRequestId).toResultMap();
        } catch (IllegalArgumentException e) {
            return validationError(rpcRequestId, e).toResultMap();
        }
    }

    public Map<String, String> handleGet(String rpcRequestId, String[] args) {
        try {
            TeamQuery query = parseSingleArg(args, TeamQuery.class);
            return manager.get(query).withRequestId(rpcRequestId).toResultMap();
        } catch (IllegalArgumentException e) {
            return validationError(rpcRequestId, e).toResultMap();
        }
    }

    public Map<String, String> handleDelete(String rpcRequestId, String[] args) {
        try {
            TeamDeleteCommand command =
                    parseSingleArg(args, TeamDeleteCommand.class);
            return manager.delete(command).toResultMap();
        } catch (IllegalArgumentException e) {
            return validationError(rpcRequestId, e).toResultMap();
        } catch (RuntimeException e) {
            return TeamCommandResult.error(rpcRequestId,
                    TeamErrorCode.INTERNAL_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage()).toResultMap();
        }
    }

    public Map<String, String> handleSend(String rpcRequestId, String[] args) {
        return handleMember(rpcRequestId, args, manager::send);
    }

    public Map<String, String> handleCancel(String rpcRequestId, String[] args) {
        return handleMember(rpcRequestId, args, manager::cancel);
    }

    public Map<String, String> handleNewSession(String rpcRequestId, String[] args) {
        return handleMember(rpcRequestId, args, manager::newSession);
    }

    public Map<String, String> handleListSessions(String rpcRequestId, String[] args) {
        return handleMember(rpcRequestId, args, manager::listSessions);
    }

    public Map<String, String> handleGetSessionHistory(String rpcRequestId, String[] args) {
        return handleMember(rpcRequestId, args, manager::getSessionHistory);
    }

    public Map<String, String> handleRestoreSession(String rpcRequestId, String[] args) {
        return handleMember(rpcRequestId, args, manager::restoreSession);
    }

    public Map<String, String> handleGetStatus(String rpcRequestId, String[] args) {
        return handleMember(rpcRequestId, args, manager::getStatus);
    }

    public Map<String, String> handleGetContextUsage(String rpcRequestId, String[] args) {
        return handleMember(rpcRequestId, args, manager::getContextUsage);
    }

    public Map<String, String> handleMemoryDream(String rpcRequestId, String[] args) {
        return handleMember(rpcRequestId, args, manager::memoryDream);
    }

    public Map<String, String> handleReadTextFile(String rpcRequestId, String[] args) {
        try {
            TeamMemberCommand command = parseSingleArg(args, TeamMemberCommand.class);
            return manager.readTextFile(rpcRequestId, command).toResultMap();
        } catch (IllegalArgumentException e) {
            return com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewResult.error(
                    rpcRequestId, "INVALID_ARGUMENT", e.getMessage(), false).toResultMap();
        } catch (RuntimeException e) {
            return com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewResult.error(
                    rpcRequestId, "INTERNAL_ERROR",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), false).toResultMap();
        }
    }

    public Map<String, String> handleTalkToDeliver(String rpcRequestId, String[] args) {
        try {
            TeamTalkToDeliverCommand command = parseSingleArg(
                    args, TeamTalkToDeliverCommand.class);
            return manager.deliverTalkTo(command).withRequestId(rpcRequestId).toResultMap();
        } catch (IllegalArgumentException e) {
            return validationError(rpcRequestId, e).toResultMap();
        } catch (RuntimeException e) {
            return TeamCommandResult.error(rpcRequestId, TeamErrorCode.INTERNAL_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage()).toResultMap();
        }
    }

    private Map<String, String> handleMember(String requestId, String[] args,
                                             MemberOperation operation) {
        try {
            TeamMemberCommand command = parseSingleArg(args, TeamMemberCommand.class);
            return operation.apply(requestId, command).toResultMap();
        } catch (IllegalArgumentException e) {
            return validationError(requestId, e).toResultMap();
        } catch (RuntimeException e) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.INTERNAL_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage()).toResultMap();
        }
    }

    @FunctionalInterface
    private interface MemberOperation {
        TeamCommandResult apply(String requestId, TeamMemberCommand command);
    }

    private <T> T parseSingleArg(String[] args, Class<T> type) {
        if (args == null || args.length != 1
                || args[0] == null || args[0].trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "cmdArgs must contain exactly one non-empty JSON payload");
        }
        try {
            T value = gson.fromJson(args[0], type);
            if (value == null) {
                throw new IllegalArgumentException("JSON payload must be an object");
            }
            return value;
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("invalid JSON payload", e);
        }
    }

    private static TeamCommandResult validationError(String requestId, Exception e) {
        return TeamCommandResult.error(requestId,
                TeamErrorCode.VALIDATION_ERROR, e.getMessage());
    }
}
