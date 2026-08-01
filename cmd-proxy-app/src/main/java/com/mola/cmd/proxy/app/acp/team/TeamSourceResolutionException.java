package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.team.model.TeamErrorCode;

public final class TeamSourceResolutionException extends Exception {

    private final TeamErrorCode code;

    public TeamSourceResolutionException(TeamErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public TeamErrorCode getCode() {
        return code;
    }
}
