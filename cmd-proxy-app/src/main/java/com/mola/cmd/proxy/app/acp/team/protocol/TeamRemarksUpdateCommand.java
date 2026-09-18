package com.mola.cmd.proxy.app.acp.team.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Updates only the human-authored responsibility remark of each Team member. */
public final class TeamRemarksUpdateCommand {

    private String schemaVersion;
    private String requestId;
    private String ownerChatterId;
    private String teamId;
    private Long expectedVersion;
    private Map<String, String> memberRemarks;

    @SuppressWarnings("unused")
    private TeamRemarksUpdateCommand() {
    }

    public TeamRemarksUpdateCommand(String schemaVersion, String requestId,
                                    String ownerChatterId, String teamId,
                                    Long expectedVersion,
                                    Map<String, String> memberRemarks) {
        this.schemaVersion = schemaVersion;
        this.requestId = requestId;
        this.ownerChatterId = ownerChatterId;
        this.teamId = teamId;
        this.expectedVersion = expectedVersion;
        this.memberRemarks = memberRemarks == null
                ? null : new LinkedHashMap<>(memberRemarks);
    }

    public String getSchemaVersion() { return schemaVersion; }

    public String getRequestId() { return requestId; }

    public String getOwnerChatterId() { return ownerChatterId; }

    public String getTeamId() { return teamId; }

    public Long getExpectedVersion() { return expectedVersion; }

    public Map<String, String> getMemberRemarks() {
        return memberRemarks == null ? null
                : Collections.unmodifiableMap(memberRemarks);
    }
}
