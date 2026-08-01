package com.mola.cmd.proxy.app.acp.team.model;

public enum TeamMemberState {
    STARTING,
    READY,
    BUSY,
    ERROR,
    CLOSING,
    CLOSED;

    public boolean isTerminal() {
        return this == CLOSED;
    }
}
