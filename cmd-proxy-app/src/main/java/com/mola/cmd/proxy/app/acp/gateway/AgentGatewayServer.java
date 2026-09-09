package com.mola.cmd.proxy.app.acp.gateway;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.gateway.model.AgentGatewayConfig;
import com.mola.cmd.proxy.app.acp.gateway.model.GatewayEvent;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.WebSocketPingPongListener;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared physical HTTP/WebSocket server for logical Agent gateway configurations. */
public final class AgentGatewayServer implements AutoCloseable {
    public static final String PREFIX = "/api/agent-gateway/v1";
    private final AgentGatewayManager manager;
    private final Server server;
    private final ScheduledExecutorService heartbeatScheduler;

    public AgentGatewayServer(AgentGatewayManager manager) {
        this.manager = java.util.Objects.requireNonNull(manager, "manager");
        this.server = new Server();
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-gateway-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        ServerConnector connector = new ServerConnector(server);
        connector.setHost(manager.getServerConfig().getBindHost());
        connector.setPort(manager.getServerConfig().getPort());
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new GatewayWebSocketServlet(manager, heartbeatScheduler)),
                PREFIX + "/ws");
        context.addServlet(new ServletHolder(new GatewayApiServlet(manager)), PREFIX + "/*");
        server.setHandler(context);
    }

    public void start() throws Exception { server.start(); }
    public int getPort() {
        if (server.getConnectors().length == 0) return -1;
        return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @Override public void close() {
        try { server.stop(); } catch (Exception ignored) { }
        heartbeatScheduler.shutdownNow();
    }

    private static final class GatewayApiServlet extends HttpServlet {
        private final AgentGatewayManager manager;
        private final GatewayAttachmentDownloader downloader = new GatewayAttachmentDownloader();

        GatewayApiServlet(AgentGatewayManager manager) { this.manager = manager; }

        @Override protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws IOException { handle(request, response); }
        @Override protected void doPost(HttpServletRequest request, HttpServletResponse response)
                throws IOException { handle(request, response); }

        private void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
            String requestId = header(request, "X-Request-Id");
            try {
                AgentGatewayConfig gateway = manager.authenticate(header(request, "Authorization"));
                manager.admitRequest(gateway);
                String path = request.getPathInfo() == null ? "/" : request.getPathInfo();
                JSONObject result;
                if ("GET".equals(request.getMethod()) && "/session".equals(path)) {
                    result = manager.status(gateway);
                } else if ("GET".equals(request.getMethod()) && "/events".equals(path)) {
                    String sessionId = required(request.getParameter("sessionId"), "sessionId");
                    long epoch = number(request.getParameter("epoch"), "epoch");
                    long after = optionalNumber(request.getParameter("afterSeq"), 0L);
                    int limit = (int) optionalNumber(request.getParameter("limit"), 200L);
                    JSONArray events = manager.events(gateway, sessionId, epoch, after, limit);
                    JSONObject data = new JSONObject(true);
                    data.put("sessionId", sessionId);
                    data.put("epoch", epoch);
                    data.put("afterSeq", after);
                    data.put("events", events);
                    data.put("hasMore", events.size() >= Math.min(Math.max(limit, 1), 1000));
                    result = envelope(true, "OK", "Events returned", data);
                } else if ("POST".equals(request.getMethod()) && "/messages".equals(path)) {
                    JSONObject body = body(request);
                    Object attachments = body.get("attachments");
                    if (attachments != null && !(attachments instanceof JSONArray)) {
                        throw invalid("attachments must be a JSON array");
                    }
                    manager.preflightSend(gateway, body);
                    List<Map<String, String>> files = downloader.download(
                            gateway, (JSONArray) attachments);
                    result = manager.send(gateway, body, files,
                            header(request, "Idempotency-Key"));
                } else if ("POST".equals(request.getMethod()) && "/session/cancel".equals(path)) {
                    result = manager.cancel(gateway, body(request),
                            header(request, "Idempotency-Key"));
                } else if ("POST".equals(request.getMethod()) && "/session/new".equals(path)) {
                    result = manager.newSession(gateway, body(request),
                            header(request, "Idempotency-Key"));
                } else {
                    throw new GatewayException(404, "NOT_FOUND", "Gateway endpoint not found", false);
                }
                write(response, 200, result);
            } catch (GatewayException error) {
                write(response, error.getHttpStatus(), AgentGatewayManager.error(error, requestId));
            } catch (Exception error) {
                GatewayException mapped = new GatewayException(500, "INTERNAL_ERROR",
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), true);
                write(response, 500, AgentGatewayManager.error(mapped, requestId));
            }
        }

        private static JSONObject body(HttpServletRequest request) throws IOException {
            StringBuilder value = new StringBuilder();
            try (BufferedReader reader = request.getReader()) {
                char[] buffer = new char[4096]; int read;
                while ((read = reader.read(buffer)) >= 0) {
                    value.append(buffer, 0, read);
                    if (value.length() > 2 * 1024 * 1024) {
                        throw new GatewayException(413, "MESSAGE_TOO_LARGE", "Request body is too large", false);
                    }
                }
            }
            try {
                JSONObject result = JSON.parseObject(value.toString());
                if (result == null) throw new IllegalArgumentException();
                return result;
            } catch (RuntimeException e) {
                throw new GatewayException(400, "INVALID_ARGUMENT", "Request body must be a JSON object", false);
            }
        }

        private static void write(HttpServletResponse response, int status, JSONObject value)
                throws IOException {
            response.setStatus(status);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            try (PrintWriter writer = response.getWriter()) { writer.write(value.toJSONString()); }
        }
    }

    private static final class GatewayWebSocketServlet extends WebSocketServlet {
        private final AgentGatewayManager manager;
        private final ScheduledExecutorService heartbeatScheduler;
        GatewayWebSocketServlet(AgentGatewayManager manager,
                                ScheduledExecutorService heartbeatScheduler) {
            this.manager = manager;
            this.heartbeatScheduler = heartbeatScheduler;
        }
        @Override public void configure(WebSocketServletFactory factory) {
            factory.getPolicy().setMaxTextMessageSize(4 * 1024 * 1024);
            factory.setCreator(new WebSocketCreator() {
                @Override public Object createWebSocket(ServletUpgradeRequest request,
                                                        ServletUpgradeResponse response) {
                    try {
                        AgentGatewayConfig gateway = manager.authenticate(
                                request.getHeader("Authorization"));
                        String protocol = request.getHeader("Sec-WebSocket-Protocol");
                        if (protocol == null || !protocol.contains("cmd-proxy.agent-gateway.v1")) {
                            response.setStatusCode(400);
                            return null;
                        }
                        response.setAcceptedSubProtocol("cmd-proxy.agent-gateway.v1");
                        return new GatewaySocket(manager, gateway, heartbeatScheduler);
                    } catch (GatewayException error) {
                        response.setStatusCode(error.getHttpStatus());
                        return null;
                    }
                }
            });
        }
    }

    private static final class GatewaySocket extends WebSocketAdapter
            implements AgentGatewayManager.Subscriber, WebSocketPingPongListener {
        private static final int MAX_PENDING = 128;
        private final AgentGatewayManager manager;
        private final AgentGatewayConfig gateway;
        private final GatewayAttachmentDownloader downloader = new GatewayAttachmentDownloader();
        private final AtomicInteger pending = new AtomicInteger();
        private final ScheduledExecutorService heartbeatScheduler;
        private AutoCloseable subscription;
        private ScheduledFuture<?> heartbeat;
        private volatile long lastPongAt;

        GatewaySocket(AgentGatewayManager manager, AgentGatewayConfig gateway,
                      ScheduledExecutorService heartbeatScheduler) {
            this.manager = manager;
            this.gateway = gateway;
            this.heartbeatScheduler = heartbeatScheduler;
        }

        @Override public void onWebSocketConnect(Session session) {
            super.onWebSocketConnect(session);
            try {
                lastPongAt = System.currentTimeMillis();
                subscription = manager.subscribe(gateway, this);
                JSONObject status = manager.status(gateway);
                JSONObject payload = new JSONObject(true);
                payload.put("connectionId", "conn_" + UUID.randomUUID());
                payload.put("heartbeatIntervalSeconds", 20);
                payload.put("maxFrameBytes", 4 * 1024 * 1024);
                payload.put("gateway", status.getJSONObject("data").getJSONObject("gateway"));
                payload.put("session", status.getJSONObject("data").getJSONObject("session"));
                send(control(null, "server.hello", payload));
                heartbeat = heartbeatScheduler.scheduleAtFixedRate(this::heartbeat,
                        20L, 20L, TimeUnit.SECONDS);
            } catch (RuntimeException error) {
                session.close(4008, error.getMessage());
            }
        }

        private void heartbeat() {
            Session session = getSession();
            if (session == null || !session.isOpen()) return;
            if (System.currentTimeMillis() - lastPongAt > TimeUnit.SECONDS.toMillis(40L)) {
                session.close(4009, "HEARTBEAT_TIMEOUT");
                return;
            }
            try {
                session.getRemote().sendPing(ByteBuffer.wrap(
                        Long.toString(System.currentTimeMillis()).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            } catch (IOException failed) {
                session.close(StatusCode.SERVER_ERROR, "HEARTBEAT_FAILED");
            }
        }

        @Override public void onWebSocketPing(ByteBuffer payload) {
            Session session = getSession();
            if (session == null || !session.isOpen()) return;
            try { session.getRemote().sendPong(payload == null ? ByteBuffer.allocate(0) : payload); }
            catch (IOException failed) { session.close(StatusCode.SERVER_ERROR, "PONG_FAILED"); }
        }

        @Override public void onWebSocketPong(ByteBuffer payload) {
            lastPongAt = System.currentTimeMillis();
        }

        @Override public void onWebSocketText(String message) {
            String requestId = null;
            try {
                manager.admitRequest(gateway);
                JSONObject frame = JSON.parseObject(message);
                if (frame == null) throw invalid("Frame must be a JSON object");
                requestId = frame.getString("requestId");
                String frameType = frame.getString("frameType");
                String type = frame.getString("type");
                JSONObject payload = frame.getJSONObject("payload");
                if (payload == null) payload = new JSONObject(true);
                if ("control".equals(frameType) && "stream.resume".equals(type)) {
                    JSONArray events = manager.events(gateway,
                            required(payload.getString("sessionId"), "sessionId"),
                            payload.getLongValue("epoch"), payload.getLongValue("afterSeq"), 1000);
                    for (int i = 0; i < events.size(); i++) send(events.getJSONObject(i));
                    JSONObject resumed = new JSONObject(true);
                    resumed.put("sessionId", payload.getString("sessionId"));
                    resumed.put("epoch", payload.getLongValue("epoch"));
                    resumed.put("fromExclusive", payload.getLongValue("afterSeq"));
                    resumed.put("replayed", events.size());
                    send(control(requestId, "stream.resumed", resumed));
                    return;
                }
                if ("control".equals(frameType) && "stream.ack".equals(type)) return;
                if (!"command".equals(frameType)) throw invalid("Unsupported frameType");
                JSONObject result;
                if ("session.get".equals(type)) result = manager.status(gateway);
                else if ("message.send".equals(type)) {
                    Object attachments = payload.get("attachments");
                    if (attachments != null && !(attachments instanceof JSONArray)) {
                        throw invalid("attachments must be a JSON array");
                    }
                    manager.preflightSend(gateway, payload);
                    List<Map<String, String>> files = downloader.download(
                            gateway, (JSONArray) attachments);
                    result = manager.send(gateway, payload, files,
                            payload.getString("idempotencyKey"));
                } else if ("session.cancel".equals(type)) {
                    result = manager.cancel(gateway, payload, payload.getString("idempotencyKey"));
                } else if ("session.new".equals(type)) {
                    result = manager.newSession(gateway, payload, payload.getString("idempotencyKey"));
                } else throw invalid("Unsupported command: " + type);
                send(commandResult(requestId, type + ".result", result));
            } catch (GatewayException error) {
                send(errorFrame(requestId, error));
            } catch (Exception error) {
                send(errorFrame(requestId, new GatewayException(500, "INTERNAL_ERROR",
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), true)));
            }
        }

        @Override public void onEvent(GatewayEvent event) { send(event.toFrame()); }
        @Override public void onClose(String reason) {
            if (getSession() != null) getSession().close(4012, reason);
        }
        @Override public void onWebSocketClose(int statusCode, String reason) {
            if (heartbeat != null) heartbeat.cancel(false);
            try { if (subscription != null) subscription.close(); } catch (Exception ignored) { }
            super.onWebSocketClose(statusCode, reason);
        }

        private void send(JSONObject frame) {
            Session session = getSession();
            if (session == null || !session.isOpen()) return;
            if (pending.incrementAndGet() > MAX_PENDING) {
                pending.decrementAndGet();
                session.close(4010, "SLOW_CONSUMER");
                return;
            }
            session.getRemote().sendString(frame.toJSONString(), new WriteCallback() {
                @Override public void writeSuccess() { pending.decrementAndGet(); }
                @Override public void writeFailed(Throwable x) {
                    pending.decrementAndGet();
                    Session current = getSession();
                    if (current != null) current.close(StatusCode.SERVER_ERROR, "WRITE_FAILED");
                }
            });
        }
    }

    private static JSONObject commandResult(String requestId, String type, JSONObject result) {
        JSONObject payload = new JSONObject(true);
        payload.put("accepted", result.getBooleanValue("accepted"));
        payload.put("code", result.getString("code"));
        payload.put("message", result.getString("message"));
        payload.put("data", result.get("data"));
        return frame("command.result", requestId, type, payload);
    }
    private static JSONObject control(String requestId, String type, JSONObject payload) {
        return frame("control", requestId, type, payload);
    }
    private static JSONObject errorFrame(String requestId, GatewayException error) {
        return frame("error", requestId, "request.error", AgentGatewayManager.error(error, requestId));
    }
    private static JSONObject frame(String frameType, String requestId, String type, Object payload) {
        JSONObject frame = new JSONObject(true);
        frame.put("schemaVersion", "1.0");
        frame.put("frameType", frameType);
        frame.put("requestId", requestId);
        frame.put("timestamp", System.currentTimeMillis());
        frame.put("type", type);
        frame.put("payload", payload);
        return frame;
    }
    private static JSONObject envelope(boolean accepted, String code, String message, Object data) {
        JSONObject value = new JSONObject(true);
        value.put("schemaVersion", "1.0"); value.put("requestId", "req_" + UUID.randomUUID());
        value.put("accepted", accepted); value.put("code", code); value.put("message", message);
        value.put("timestamp", System.currentTimeMillis()); value.put("data", data); return value;
    }
    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name); return value == null ? "" : value.trim();
    }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw invalid(name + " is required");
        return value.trim();
    }
    private static long number(String value, String name) {
        try { return Long.parseLong(required(value, name)); }
        catch (NumberFormatException e) { throw invalid(name + " must be a number"); }
    }
    private static long optionalNumber(String value, long fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        try { return Long.parseLong(value.trim()); }
        catch (NumberFormatException e) { throw invalid("numeric query parameter is invalid"); }
    }
    private static GatewayException invalid(String message) {
        return new GatewayException(400, "INVALID_ARGUMENT", message, false);
    }
}
