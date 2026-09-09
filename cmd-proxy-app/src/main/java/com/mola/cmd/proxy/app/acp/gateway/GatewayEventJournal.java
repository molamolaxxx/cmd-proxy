package com.mola.cmd.proxy.app.acp.gateway;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.gateway.model.GatewayEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

/** Durable event sequence and replay store. Local append completes before live publish. */
public final class GatewayEventJournal implements AutoCloseable {
    private final String jdbcUrl;
    private volatile boolean closed;

    public GatewayEventJournal(Path database) throws Exception {
        Path absolute = database.toAbsolutePath();
        if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
        this.jdbcUrl = "jdbc:sqlite:" + absolute;
        initialize();
    }

    private void initialize() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("CREATE TABLE IF NOT EXISTS gateway_events ("
                    + "gateway_id TEXT NOT NULL, session_id TEXT NOT NULL, epoch INTEGER NOT NULL,"
                    + "event_seq INTEGER NOT NULL, event_id TEXT NOT NULL, turn_id TEXT, card_id TEXT,"
                    + "timestamp INTEGER NOT NULL, type TEXT NOT NULL, payload TEXT NOT NULL,"
                    + "source TEXT NOT NULL, PRIMARY KEY(gateway_id,session_id,epoch,event_seq),"
                    + "UNIQUE(event_id))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_gateway_events_time "
                    + "ON gateway_events(timestamp)");
            statement.execute("CREATE TABLE IF NOT EXISTS gateway_requests ("
                    + "gateway_id TEXT NOT NULL, operation TEXT NOT NULL, idempotency_key TEXT NOT NULL,"
                    + "fingerprint TEXT NOT NULL, response TEXT NOT NULL, created_at INTEGER NOT NULL,"
                    + "PRIMARY KEY(gateway_id,operation,idempotency_key))");
        }
    }

    public synchronized GatewayEvent append(String gatewayId, String sessionId, long epoch,
                                             String turnId, String cardId, String type,
                                             JSONObject payload, JSONObject source) throws Exception {
        requireOpen();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                long next;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT COALESCE(MAX(event_seq),0)+1 FROM gateway_events "
                                + "WHERE gateway_id=? AND session_id=? AND epoch=?")) {
                    query.setString(1, gatewayId);
                    query.setString(2, sessionId);
                    query.setLong(3, epoch);
                    try (ResultSet values = query.executeQuery()) {
                        next = values.next() ? values.getLong(1) : 1L;
                    }
                }
                String eventId = "evt_" + UUID.randomUUID().toString();
                long timestamp = System.currentTimeMillis();
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO gateway_events(gateway_id,session_id,epoch,event_seq,event_id,"
                                + "turn_id,card_id,timestamp,type,payload,source) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                    insert.setString(1, gatewayId);
                    insert.setString(2, sessionId);
                    insert.setLong(3, epoch);
                    insert.setLong(4, next);
                    insert.setString(5, eventId);
                    insert.setString(6, turnId);
                    insert.setString(7, cardId);
                    insert.setLong(8, timestamp);
                    insert.setString(9, type);
                    insert.setString(10, JSON.toJSONString(payload == null
                            ? new JSONObject(true) : payload));
                    insert.setString(11, JSON.toJSONString(source == null
                            ? new JSONObject(true) : source));
                    insert.executeUpdate();
                }
                connection.commit();
                return new GatewayEvent(gatewayId, eventId, next, sessionId, epoch,
                        turnId, cardId, timestamp, type, payload, source);
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    public JSONArray list(String gatewayId, String sessionId, long epoch,
                          long afterSeq, int limit) throws Exception {
        requireOpen();
        JSONArray events = new JSONArray();
        try (Connection connection = open(); PreparedStatement query = connection.prepareStatement(
                "SELECT event_seq,event_id,turn_id,card_id,timestamp,type,payload,source "
                        + "FROM gateway_events WHERE gateway_id=? AND session_id=? AND epoch=? "
                        + "AND event_seq>? ORDER BY event_seq LIMIT ?")) {
            query.setString(1, gatewayId);
            query.setString(2, sessionId);
            query.setLong(3, epoch);
            query.setLong(4, Math.max(0L, afterSeq));
            query.setInt(5, Math.max(1, Math.min(limit, 1000)));
            try (ResultSet values = query.executeQuery()) {
                while (values.next()) {
                    GatewayEvent event = new GatewayEvent(gatewayId, values.getString("event_id"),
                            values.getLong("event_seq"), sessionId, epoch,
                            values.getString("turn_id"), values.getString("card_id"),
                            values.getLong("timestamp"), values.getString("type"),
                            JSON.parseObject(values.getString("payload")),
                            JSON.parseObject(values.getString("source")));
                    events.add(event.toFrame());
                }
            }
        }
        return events;
    }

    public long lastSeq(String gatewayId, String sessionId, long epoch) throws Exception {
        requireOpen();
        try (Connection connection = open(); PreparedStatement query = connection.prepareStatement(
                "SELECT COALESCE(MAX(event_seq),0) FROM gateway_events "
                        + "WHERE gateway_id=? AND session_id=? AND epoch=?")) {
            query.setString(1, gatewayId);
            query.setString(2, sessionId);
            query.setLong(3, epoch);
            try (ResultSet values = query.executeQuery()) {
                return values.next() ? values.getLong(1) : 0L;
            }
        }
    }

    /** Last durable session cursor for one logical gateway, used across process restarts. */
    public JSONObject latestPosition(String gatewayId) throws Exception {
        requireOpen();
        try (Connection connection = open(); PreparedStatement query = connection.prepareStatement(
                "SELECT session_id,epoch,event_seq FROM gateway_events WHERE gateway_id=? "
                        + "ORDER BY epoch DESC,event_seq DESC LIMIT 1")) {
            query.setString(1, gatewayId);
            try (ResultSet values = query.executeQuery()) {
                if (!values.next()) return null;
                JSONObject result = new JSONObject(true);
                result.put("sessionId", values.getString("session_id"));
                result.put("epoch", values.getLong("epoch"));
                result.put("eventSeq", values.getLong("event_seq"));
                return result;
            }
        }
    }

    public synchronized JSONObject idempotent(String gatewayId, String operation,
                                              String key, String fingerprint,
                                              Operation action) throws Exception {
        requireOpen();
        if (key == null || key.trim().isEmpty() || key.length() > 128) {
            throw new GatewayException(400, "INVALID_ARGUMENT", "Idempotency-Key is required", false);
        }
        for (int i = 0; i < key.length(); i++) {
            char value = key.charAt(i);
            if (value < 0x20 || value > 0x7e) {
                throw new GatewayException(400, "INVALID_ARGUMENT",
                        "Idempotency-Key must contain printable ASCII characters", false);
            }
        }
        try (Connection connection = open(); PreparedStatement query = connection.prepareStatement(
                "SELECT fingerprint,response FROM gateway_requests WHERE gateway_id=? "
                        + "AND operation=? AND idempotency_key=?")) {
            query.setString(1, gatewayId);
            query.setString(2, operation);
            query.setString(3, key);
            try (ResultSet value = query.executeQuery()) {
                if (value.next()) {
                    if (!fingerprint.equals(value.getString("fingerprint"))) {
                        throw new GatewayException(409, "IDEMPOTENCY_CONFLICT",
                                "Idempotency-Key was used for a different request", false);
                    }
                    return JSON.parseObject(value.getString("response"));
                }
            }
        }
        JSONObject response = action.run();
        try (Connection connection = open(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO gateway_requests(gateway_id,operation,idempotency_key,fingerprint,response,created_at)"
                        + " VALUES(?,?,?,?,?,?)")) {
            insert.setString(1, gatewayId);
            insert.setString(2, operation);
            insert.setString(3, key);
            insert.setString(4, fingerprint);
            insert.setString(5, response.toJSONString());
            insert.setLong(6, System.currentTimeMillis());
            insert.executeUpdate();
        }
        return response;
    }

    public void purgeOlderThan(long timestamp) throws Exception {
        requireOpen();
        try (Connection connection = open(); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM gateway_events WHERE timestamp<?")) {
            delete.setLong(1, timestamp);
            delete.executeUpdate();
        }
    }

    private Connection open() throws Exception {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Agent gateway event journal is closed");
    }

    @Override public void close() { closed = true; }

    public interface Operation { JSONObject run() throws Exception; }
}
