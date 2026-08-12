package com.mola.cmd.proxy.app.acp.team.event;

import com.mola.cmd.proxy.app.acp.team.protocol.TeamTransportProtocol;
import com.mola.cmd.proxy.app.acp.acpclient.listener.RpcCallbackRetry;
import com.mola.cmd.proxy.client.provider.CmdReceiver;
import com.mola.cmd.proxy.client.resp.CmdResponseContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class RpcTeamEventSink implements TeamEventSink {

    static final int DEFAULT_QUEUE_CAPACITY = 1024;

    private static final Logger logger =
            LoggerFactory.getLogger(RpcTeamEventSink.class);

    private final TeamCallbackSender sender;
    private final int queueCapacity;
    private final ThreadPoolExecutor deliveryExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong rejectedEventCount = new AtomicLong();

    public RpcTeamEventSink() {
        this((command, group, response) ->
                CmdReceiver.INSTANCE.callback(command, group, response));
    }

    RpcTeamEventSink(TeamCallbackSender sender) {
        this(sender, DEFAULT_QUEUE_CAPACITY);
    }

    RpcTeamEventSink(TeamCallbackSender sender, int queueCapacity) {
        this.sender = java.util.Objects.requireNonNull(sender, "sender");
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.queueCapacity = queueCapacity;
        this.deliveryExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(
                            runnable, "team-event-callback-delivery");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void publish(TeamEventEnvelope event) {
        tryPublish(event);
    }

    @Override
    public boolean tryPublish(TeamEventEnvelope event) {
        java.util.Objects.requireNonNull(event, "event");
        CmdResponseContent response = new CmdResponseContent(event.getEventId(),
                TeamEventCodec.toResultMap(event));
        try {
            deliveryExecutor.execute(() -> RpcCallbackRetry.run(
                    "Fast Team event " + event.getEventId(), () ->
                            sender.send(TeamTransportProtocol.EVENT_COMMAND,
                                    event.getTransportGroup(), response)));
            return true;
        } catch (RejectedExecutionException rejected) {
            long rejectedCount = rejectedEventCount.incrementAndGet();
            logger.warn("Fast Team event callback rejected; projection event dropped,"
                            + " eventId={}, teamId={}, eventSeq={}, reason={},"
                            + " queueSize={}, queueCapacity={}, rejectedCount={}",
                    event.getEventId(), event.getTeamId(), event.getEventSeq(),
                    closed.get() ? "sink_closed" : "queue_full",
                    deliveryExecutor.getQueue().size(), queueCapacity,
                    rejectedCount);
            return false;
        }
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public int getQueuedEventCount() {
        return deliveryExecutor.getQueue().size();
    }

    public long getRejectedEventCount() {
        return rejectedEventCount.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<Runnable> dropped = deliveryExecutor.shutdownNow();
        if (!dropped.isEmpty()) {
            long rejectedCount = rejectedEventCount.addAndGet(dropped.size());
            logger.warn("Fast Team event callback sink closed with queued events;"
                            + " dropped={}, rejectedCount={}",
                    dropped.size(), rejectedCount);
        }
    }
}
