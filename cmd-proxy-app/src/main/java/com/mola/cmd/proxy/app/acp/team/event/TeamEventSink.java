package com.mola.cmd.proxy.app.acp.team.event;

@FunctionalInterface
public interface TeamEventSink {

    TeamEventSink NOOP = event -> {
    };

    void publish(TeamEventEnvelope event);
}
