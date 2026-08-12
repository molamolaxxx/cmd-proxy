package com.mola.cmd.proxy.app.acp.team.event;

@FunctionalInterface
public interface TeamEventSink extends AutoCloseable {

    TeamEventSink NOOP = event -> {
    };

    void publish(TeamEventEnvelope event);

    /**
     * Non-blocking admission hook for events whose caller must know whether the
     * event was accepted for delivery. Synchronous/in-memory sinks accept by
     * default after publishing.
     */
    default boolean tryPublish(TeamEventEnvelope event) {
        publish(event);
        return true;
    }

    @Override
    default void close() {
    }
}
