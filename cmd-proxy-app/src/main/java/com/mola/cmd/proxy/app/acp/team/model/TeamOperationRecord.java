package com.mola.cmd.proxy.app.acp.team.model;

public final class TeamOperationRecord {

    public enum Operation {
        CREATE,
        DELETE
    }

    public enum Status {
        ACCEPTED,
        SUCCEEDED,
        FAILED
    }

    private String requestId;
    private Operation operation;
    private String payloadHash;
    private String teamId;
    private Status status;
    private String resultSnapshot;
    private long createdAt;
    private long expiresAt;

    @SuppressWarnings("unused")
    private TeamOperationRecord() {
    }

    public TeamOperationRecord(String requestId, Operation operation, String payloadHash,
                               String teamId, Status status, String resultSnapshot,
                               long createdAt, long expiresAt) {
        this.requestId = TeamError.requireText(requestId, "requestId");
        this.operation = java.util.Objects.requireNonNull(operation, "operation");
        this.payloadHash = TeamError.requireText(payloadHash, "payloadHash");
        this.teamId = TeamError.requireText(teamId, "teamId");
        this.status = java.util.Objects.requireNonNull(status, "status");
        this.resultSnapshot = resultSnapshot;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getRequestId() {
        return requestId;
    }

    public Operation getOperation() {
        return operation;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public String getTeamId() {
        return teamId;
    }

    public Status getStatus() {
        return status;
    }

    public String getResultSnapshot() {
        return resultSnapshot;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }
}
