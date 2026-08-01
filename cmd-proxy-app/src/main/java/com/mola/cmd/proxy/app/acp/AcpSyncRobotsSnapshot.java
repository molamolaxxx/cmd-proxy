package com.mola.cmd.proxy.app.acp;

import com.mola.cmd.proxy.app.acp.team.protocol.TeamTransportDescriptor;
import com.mola.cmd.proxy.app.acp.team.protocol.TeamTransportProtocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可重复读取的 acpSyncRobots 权威快照。
 *
 * <p>首次启动的 callback 与 MolaChat 重连后的主动握手必须读取同一份完整快照，
 * 避免普通 robot 已恢复但 Fast Team discovery 丢失。</p>
 */
public final class AcpSyncRobotsSnapshot {

    private final AtomicReference<State> state;

    public AcpSyncRobotsSnapshot(String robotsJson, String visibleChatterIdsJson,
                                 TeamTransportDescriptor teamDescriptor) {
        state = new AtomicReference<>(new State(
                normalizeArray(robotsJson), normalizeArray(visibleChatterIdsJson),
                requireDescriptor(teamDescriptor)));
    }

    /** 更新普通 ACP 投影，同时保留当前 Team descriptor。 */
    public void updateOrdinary(String robotsJson, String visibleChatterIdsJson) {
        State current;
        State updated;
        do {
            current = state.get();
            updated = new State(normalizeArray(robotsJson),
                    normalizeArray(visibleChatterIdsJson), current.teamDescriptor);
        } while (!state.compareAndSet(current, updated));
    }

    /** 更新 Team readiness/command descriptor，同时保留普通 ACP 投影。 */
    public void updateTeamDescriptor(TeamTransportDescriptor teamDescriptor) {
        TeamTransportDescriptor required = requireDescriptor(teamDescriptor);
        State current;
        State updated;
        do {
            current = state.get();
            updated = new State(current.robotsJson,
                    current.visibleChatterIdsJson, required);
        } while (!state.compareAndSet(current, updated));
    }

    /**
     * 返回独立、不可变的完整 resultMap，调用方不能污染后续重连快照。
     */
    public Map<String, String> resultMap() {
        State current = state.get();
        Map<String, String> result = new LinkedHashMap<>();
        result.put("robots", current.robotsJson);
        result.put("visibleChatterIds", current.visibleChatterIdsJson);
        result.putAll(TeamTransportProtocol.discoveryFields(current.teamDescriptor));
        return Collections.unmodifiableMap(result);
    }

    /** heartbeat 只能发布已完成 Team 业务注册的快照。 */
    public boolean isBusinessCommandsReady() {
        return state.get().teamDescriptor.isBusinessCommandsReady();
    }

    private static String normalizeArray(String json) {
        return json == null || json.trim().isEmpty() ? "[]" : json;
    }

    private static TeamTransportDescriptor requireDescriptor(
            TeamTransportDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("teamDescriptor is required");
        }
        return descriptor;
    }

    private static final class State {
        private final String robotsJson;
        private final String visibleChatterIdsJson;
        private final TeamTransportDescriptor teamDescriptor;

        private State(String robotsJson, String visibleChatterIdsJson,
                      TeamTransportDescriptor teamDescriptor) {
            this.robotsJson = robotsJson;
            this.visibleChatterIdsJson = visibleChatterIdsJson;
            this.teamDescriptor = teamDescriptor;
        }
    }
}
