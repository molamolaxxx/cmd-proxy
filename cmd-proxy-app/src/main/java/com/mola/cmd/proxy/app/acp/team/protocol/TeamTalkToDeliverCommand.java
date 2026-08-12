package com.mola.cmd.proxy.app.acp.team.protocol;

public final class TeamTalkToDeliverCommand {
    private String schemaVersion;
    private String requestId;
    private String messageId;
    private String ownerChatterId;
    private String teamId;
    private String senderTeamMemberId;
    private String targetTeamMemberId;
    private String content;
    private int depth;
    private long createdAt;
    private long expiresAt;

    @SuppressWarnings("unused") private TeamTalkToDeliverCommand() { }

    public TeamTalkToDeliverCommand(String schemaVersion, String requestId,
                                    String messageId, String ownerChatterId,
                                    String teamId, String senderTeamMemberId,
                                    String targetTeamMemberId, String content,
                                    int depth, long createdAt, long expiresAt) {
        this.schemaVersion = schemaVersion; this.requestId = requestId;
        this.messageId = messageId; this.ownerChatterId = ownerChatterId;
        this.teamId = teamId; this.senderTeamMemberId = senderTeamMemberId;
        this.targetTeamMemberId = targetTeamMemberId; this.content = content;
        this.depth = depth; this.createdAt = createdAt; this.expiresAt = expiresAt;
    }
    public String getSchemaVersion() { return schemaVersion; }
    public String getRequestId() { return requestId; }
    public String getMessageId() { return messageId; }
    public String getOwnerChatterId() { return ownerChatterId; }
    public String getTeamId() { return teamId; }
    public String getSenderTeamMemberId() { return senderTeamMemberId; }
    public String getTargetTeamMemberId() { return targetTeamMemberId; }
    public String getContent() { return content; }
    public int getDepth() { return depth; }
    public long getCreatedAt() { return createdAt; }
    public long getExpiresAt() { return expiresAt; }
}
