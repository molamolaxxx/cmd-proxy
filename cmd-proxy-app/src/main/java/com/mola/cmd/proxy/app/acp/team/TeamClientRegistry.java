package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.team.runtime.TeamClientKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * 独立于普通 AcpClientRegistry 的 Team client 注册表。
 */
public final class TeamClientRegistry {

    private static final Logger logger = LoggerFactory.getLogger(TeamClientRegistry.class);

    private final ConcurrentHashMap<TeamClientKey, AcpClient> clients =
            new ConcurrentHashMap<>();

    public boolean register(String teamId, String teamMemberId, AcpClient client) {
        validateIdentity(teamId, teamMemberId, client);
        return clients.putIfAbsent(new TeamClientKey(teamId, teamMemberId), client) == null;
    }

    public Optional<AcpClient> get(String teamId, String teamMemberId) {
        return Optional.ofNullable(clients.get(new TeamClientKey(teamId, teamMemberId)));
    }

    public Optional<AcpClient> remove(String teamId, String teamMemberId) {
        return Optional.ofNullable(clients.remove(new TeamClientKey(teamId, teamMemberId)));
    }

    public List<IOException> closeTeam(String teamId) {
        List<IOException> errors = new ArrayList<>();
        for (Map.Entry<TeamClientKey, AcpClient> entry : clients.entrySet()) {
            if (entry.getKey().getTeamId().equals(teamId)
                    && clients.remove(entry.getKey(), entry.getValue())) {
                close(entry.getKey(), entry.getValue(), errors);
            }
        }
        return Collections.unmodifiableList(errors);
    }

    public List<IOException> closeAll() {
        return closeAll(false);
    }

    /** 全局 stop 专用：Team client 只落盘 pending，不提交新的记忆模型调用。 */
    public List<IOException> closeAllForShutdown() {
        return closeAll(true);
    }

    private List<IOException> closeAll(boolean deferMemoryExtraction) {
        List<IOException> errors = new ArrayList<>();
        for (Map.Entry<TeamClientKey, AcpClient> entry : clients.entrySet()) {
            if (clients.remove(entry.getKey(), entry.getValue())) {
                close(entry.getKey(), entry.getValue(), errors,
                        deferMemoryExtraction);
            }
        }
        return Collections.unmodifiableList(errors);
    }

    public int size() {
        return clients.size();
    }

    public int size(String teamId) {
        int count = 0;
        for (TeamClientKey key : clients.keySet()) {
            if (key.getTeamId().equals(teamId)) {
                count++;
            }
        }
        return count;
    }

    public Set<String> teamIds() {
        Set<String> result = new HashSet<>();
        for (TeamClientKey key : clients.keySet()) {
            result.add(key.getTeamId());
        }
        return Collections.unmodifiableSet(result);
    }

    private void close(TeamClientKey key, AcpClient client, List<IOException> errors) {
        close(key, client, errors, false);
    }

    private void close(TeamClientKey key, AcpClient client, List<IOException> errors,
                       boolean deferMemoryExtraction) {
        try {
            if (deferMemoryExtraction) {
                client.closeForShutdown();
            } else {
                client.close();
            }
        } catch (IOException e) {
            errors.add(e);
            logger.warn("关闭 Team AcpClient 失败, key={}", key, e);
        }
    }

    private static void validateIdentity(String teamId, String teamMemberId, AcpClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        AcpClientIdentity identity = client.getClientIdentity();
        if (identity.getScope() != AcpClientIdentity.Scope.TEAM
                || !teamId.equals(identity.getTeamId())
                || !teamMemberId.equals(identity.getTeamMemberId())) {
            throw new IllegalArgumentException(
                    "Team client identity does not match registry key");
        }
    }
}
