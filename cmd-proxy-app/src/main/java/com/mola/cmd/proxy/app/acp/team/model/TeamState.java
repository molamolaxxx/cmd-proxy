package com.mola.cmd.proxy.app.acp.team.model;

public enum TeamState {
    CREATING,
    READY,
    RECOVERING,
    ROLLING_BACK,
    FAILED,
    DELETING,
    DELETED,
    DELETED_WITH_WARNINGS;

    public boolean isTerminal() {
        return this == DELETED || this == DELETED_WITH_WARNINGS;
    }
}
