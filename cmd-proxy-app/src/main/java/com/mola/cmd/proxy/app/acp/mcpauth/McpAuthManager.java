package com.mola.cmd.proxy.app.acp.mcpauth;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Authoritative channel-neutral runtime and persisted policy state for MCP authorization. */
public final class McpAuthManager {
    public static final String AUTH_SESSION_HEADER = "X-Cmd-Proxy-Auth-Session-Id";
    public static final String AUTH_BASE_URL_HEADER = "X-Cmd-Proxy-Auth-Base-Url";
    public static final String AUTH_SESSION_ENV = "CMD_PROXY_AUTH_SESSION_ID";
    public static final String AUTH_BASE_URL_ENV = "CMD_PROXY_AUTH_BASE_URL";
    private static final long BINDING_TTL_MS = 30L * 60L * 1000L;
    private static final McpAuthManager INSTANCE =
            new McpAuthManager(CmdProxyHome.resolve("mcpAuthConfig.json"));

    private final SecureRandom random = new SecureRandom();
    private final Path configPath;
    private final Object configLock = new Object();
    private final ConcurrentHashMap<String, String> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ActivePrincipal> activePrincipals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> principalExpectedTurns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Registration> registrations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Policy> policies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, KnownPrincipal> knownPrincipals = new ConcurrentHashMap<>();
    private volatile String baseUrl = "";

    public static McpAuthManager getInstance() { return INSTANCE; }

    McpAuthManager(Path configPath) {
        this.configPath = configPath;
        load();
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = clean(baseUrl);
    }

    public String getBaseUrl() { return baseUrl; }

    public void clearBaseUrl(String expectedBaseUrl) {
        String expected = clean(expectedBaseUrl);
        if (baseUrl.equals(expected)) baseUrl = "";
    }

    public String createSession(String clientIdentity) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(id, clean(clientIdentity));
        return id;
    }

    public void removeSession(String authSessionId) {
        sessions.remove(authSessionId);
        activePrincipals.remove(authSessionId);
        principalExpectedTurns.remove(authSessionId);
        registrations.keySet().removeIf(key -> key.startsWith(authSessionId + "\n"));
    }

    public void bind(String authSessionId, AuthPrincipalContext context, String turnId) {
        if (context == null) { clearBinding(authSessionId); return; }
        long now = System.currentTimeMillis();
        activePrincipals.put(authSessionId, new ActivePrincipal(
                context.getPrincipalId(), context.getDisplayName(), context.getSourceType(),
                context.getSourceId(), clean(turnId), now, now + BINDING_TTL_MS));
        principalExpectedTurns.put(authSessionId, clean(turnId));
    }

    public void unbind(String authSessionId, String turnId) {
        ActivePrincipal current = activePrincipals.get(authSessionId);
        if (current != null && current.turnId.equals(clean(turnId))) {
            activePrincipals.remove(authSessionId, current);
        }
        principalExpectedTurns.remove(authSessionId, clean(turnId));
    }

    public void clearBinding(String authSessionId) {
        activePrincipals.remove(authSessionId);
        principalExpectedTurns.remove(authSessionId);
    }

    public void recordPrincipal(AuthPrincipalContext context) {
        if (context == null) return;
        String id = clean(context.getPrincipalId());
        if (id.isEmpty()) return;
        long now = System.currentTimeMillis();
        knownPrincipals.compute(id, (ignored, old) -> old == null
                ? new KnownPrincipal(id, clean(context.getDisplayName()), context.getSourceType(),
                        context.getSourceId(), now, now)
                : old.seen(clean(context.getDisplayName()), context.getSourceType(),
                        context.getSourceId(), now));
        persistQuietly();
    }

    public JSONObject register(JSONObject request) {
        String authSessionId = text(request, "authSessionId");
        String serverId = text(request, "serverId");
        String name = text(request, "name");
        if (authSessionId.isEmpty() || serverId.isEmpty() || name.isEmpty()) {
            return failure("INVALID_REQUEST", "authSessionId, serverId and name are required");
        }
        if (!sessions.containsKey(authSessionId)) {
            return failure("AUTH_SESSION_NOT_FOUND", "auth session is not active");
        }
        long now = System.currentTimeMillis();
        JSONArray tools = request.getJSONArray("tools");
        Registration registration = new Registration(authSessionId, serverId, name,
                tools == null ? new JSONArray() : tools, now);
        registrations.put(key(authSessionId, serverId), registration);
        policies.computeIfAbsent(serverId, ignored -> new Policy(serverId, name, false,
                Collections.emptyList()));
        persistQuietly();
        JSONObject result = success();
        result.put("serverId", serverId);
        result.put("authEnabled", policies.get(serverId).authEnabled);
        result.put("registeredAt", now);
        result.put("serverTime", now);
        return result;
    }

    public JSONObject check(JSONObject request) {
        String authSessionId = text(request, "authSessionId");
        String serverId = text(request, "serverId");
        if (authSessionId.isEmpty() || serverId.isEmpty()) {
            return decision(false, "INVALID_REQUEST", serverId, null,
                    "authSessionId and serverId are required");
        }
        if (!sessions.containsKey(authSessionId)) {
            return decision(false, "AUTH_SESSION_NOT_FOUND", serverId, null,
                    "auth session is not active");
        }
        if (!registrations.containsKey(key(authSessionId, serverId))) {
            return decision(false, "SERVER_NOT_REGISTERED", serverId, null,
                    "MCP Server 尚未完成注册");
        }
        Policy policy = policies.get(serverId);
        if (policy == null || !policy.authEnabled) {
            return decision(true, "AUTH_DISABLED", serverId, null, null);
        }
        ActivePrincipal principal = activePrincipals.get(authSessionId);
        long now = System.currentTimeMillis();
        if (principal == null || principal.expiresAt <= now) {
            if (principal != null) activePrincipals.remove(authSessionId, principal);
            if (principalExpectedTurns.containsKey(authSessionId)) {
                return decision(false, "PRINCIPAL_BINDING_EXPIRED", serverId, null,
                        "当前用户身份绑定已过期");
            }
            return decision(true, "NO_PRINCIPAL_ALLOWED", serverId, null, null);
        }
        if (!policy.allowedPrincipalIds.contains(principal.principalId)) {
            return decision(false, "PRINCIPAL_NOT_ALLOWED", serverId, principal,
                    "当前用户无权访问该 MCP Server");
        }
        return decision(true, "PRINCIPAL_ALLOWED", serverId, principal, null);
    }

    public JSONArray serverSnapshot() {
        Map<String, JSONObject> result = new java.util.TreeMap<>();
        for (Policy policy : policies.values()) result.put(policy.serverId, policy.toJson());
        for (Registration registration : registrations.values()) {
            JSONObject server = result.computeIfAbsent(registration.serverId,
                    ignored -> new JSONObject(true));
            server.put("serverId", registration.serverId);
            server.put("name", registration.name);
            server.put("tools", registration.tools);
            server.put("lastSeenAt", registration.lastSeenAt);
            server.put("online", true);
        }
        JSONArray array = new JSONArray();
        array.addAll(result.values());
        return array;
    }

    public JSONArray principalSnapshot() {
        List<KnownPrincipal> values = new ArrayList<>(knownPrincipals.values());
        values.sort((a, b) -> Long.compare(b.lastSeenAt, a.lastSeenAt));
        JSONArray array = new JSONArray();
        for (KnownPrincipal value : values) array.add(value.toJson());
        return array;
    }

    public JSONObject updatePolicy(JSONObject request) {
        String serverId = text(request, "serverId");
        if (serverId.isEmpty()) return failure("INVALID_REQUEST", "serverId is required");
        Policy previous = policies.get(serverId);
        String name = previous == null ? serverId : previous.name;
        JSONArray allowed = request.getJSONArray("allowedPrincipalIds");
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (allowed != null) for (Object value : allowed) {
            String id = clean(String.valueOf(value));
            if (!id.isEmpty()) ids.add(id);
        }
        policies.put(serverId, new Policy(serverId, name,
                request.getBooleanValue("authEnabled"), new ArrayList<>(ids)));
        persistQuietly();
        return success();
    }

    private JSONObject decision(boolean allowed, String code, String serverId,
                                ActivePrincipal principal, String message) {
        JSONObject result = success();
        result.put("allowed", allowed);
        result.put("code", code);
        result.put("serverId", serverId);
        result.put("principalId", principal == null ? null : principal.principalId);
        if (principal != null) {
            result.put("sourceType", principal.sourceType);
            result.put("sourceId", principal.sourceId);
            result.put("turnId", principal.turnId);
            result.put("expiresAt", principal.expiresAt);
        }
        if (message != null) result.put("message", message);
        result.put("serverTime", System.currentTimeMillis());
        return result;
    }

    private void load() {
        if (!Files.isRegularFile(configPath)) return;
        try {
            JSONObject root = JSON.parseObject(new String(Files.readAllBytes(configPath),
                    StandardCharsets.UTF_8));
            JSONArray serverArray = root.getJSONArray("servers");
            if (serverArray != null) for (int i = 0; i < serverArray.size(); i++) {
                Policy policy = Policy.from(serverArray.getJSONObject(i));
                if (policy != null) policies.put(policy.serverId, policy);
            }
            JSONArray principalArray = root.getJSONArray("principals");
            if (principalArray != null) for (int i = 0; i < principalArray.size(); i++) {
                KnownPrincipal principal = KnownPrincipal.from(principalArray.getJSONObject(i));
                if (principal != null) knownPrincipals.put(principal.principalId, principal);
            }
        } catch (Exception ignored) { }
    }

    private void persistQuietly() {
        synchronized (configLock) {
            try {
                JSONObject root = new JSONObject(true);
                root.put("servers", serverSnapshot());
                root.put("principals", principalSnapshot());
                Path parent = configPath.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                Path temp = Files.createTempFile(parent, configPath.getFileName().toString(), ".tmp");
                Files.write(temp, JSON.toJSONString(root, SerializerFeature.PrettyFormat,
                        SerializerFeature.SortField).getBytes(StandardCharsets.UTF_8));
                Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) { }
        }
    }

    private static String key(String session, String server) { return session + "\n" + server; }
    private static String text(JSONObject object, String field) {
        return object == null ? "" : clean(object.getString(field));
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static JSONObject success() { JSONObject o = new JSONObject(true); o.put("success", true); return o; }
    private static JSONObject failure(String code, String message) {
        JSONObject o = new JSONObject(true); o.put("success", false); o.put("code", code); o.put("message", message); return o;
    }

    private static final class ActivePrincipal {
        final String principalId, displayName, sourceType, sourceId, turnId;
        final long boundAt, expiresAt;
        ActivePrincipal(String principalId, String displayName, String sourceType,
                        String sourceId, String turnId,
                        long boundAt, long expiresAt) {
            this.principalId=principalId; this.displayName=displayName;
            this.sourceType=sourceType; this.sourceId=sourceId;
            this.turnId=turnId; this.boundAt=boundAt; this.expiresAt=expiresAt;
        }
    }
    private static final class Registration {
        final String authSessionId, serverId, name; final JSONArray tools; final long lastSeenAt;
        Registration(String authSessionId, String serverId, String name, JSONArray tools, long time) {
            this.authSessionId=authSessionId; this.serverId=serverId; this.name=name;
            this.tools=JSON.parseArray(tools.toJSONString()); this.lastSeenAt=time;
        }
    }
    private static final class Policy {
        final String serverId, name; final boolean authEnabled; final List<String> allowedPrincipalIds;
        Policy(String serverId, String name, boolean enabled, List<String> ids) {
            this.serverId=serverId; this.name=name; this.authEnabled=enabled;
            this.allowedPrincipalIds=Collections.unmodifiableList(new ArrayList<>(ids));
        }
        JSONObject toJson() { JSONObject o=new JSONObject(true); o.put("serverId",serverId); o.put("name",name); o.put("authEnabled",authEnabled); o.put("allowedPrincipalIds",allowedPrincipalIds); return o; }
        static Policy from(JSONObject o) {
            if (o==null || clean(o.getString("serverId")).isEmpty()) return null;
            JSONArray a=o.getJSONArray("allowedPrincipalIds"); List<String> ids=new ArrayList<>();
            if(a!=null) for(Object v:a) if(!clean(String.valueOf(v)).isEmpty()) ids.add(clean(String.valueOf(v)));
            return new Policy(clean(o.getString("serverId")), clean(o.getString("name")), o.getBooleanValue("authEnabled"), ids);
        }
    }
    private static final class KnownPrincipal {
        final String principalId, displayName; final LinkedHashSet<String> sources; final long firstSeenAt,lastSeenAt;
        KnownPrincipal(String id,String name,String type,String source,long first,long last){this.principalId=id;this.displayName=name;this.sources=new LinkedHashSet<>();addSource(type,source);this.firstSeenAt=first;this.lastSeenAt=last;}
        void addSource(String type,String id){String value=clean(type)+(clean(id).isEmpty()?"":":"+clean(id));if(!value.isEmpty())sources.add(value);}
        KnownPrincipal seen(String name,String type,String id,long now){KnownPrincipal p=new KnownPrincipal(principalId,name.isEmpty()?displayName:name,"","",firstSeenAt,now);p.sources.addAll(sources);p.addSource(type,id);return p;}
        JSONObject toJson(){JSONObject o=new JSONObject(true);o.put("principalId",principalId);o.put("displayName",displayName);o.put("sources",sources);o.put("firstSeenAt",firstSeenAt);o.put("lastSeenAt",lastSeenAt);return o;}
        static KnownPrincipal from(JSONObject o){if(o==null||clean(o.getString("principalId")).isEmpty())return null;KnownPrincipal p=new KnownPrincipal(clean(o.getString("principalId")),clean(o.getString("displayName")),"","",o.getLongValue("firstSeenAt"),o.getLongValue("lastSeenAt"));JSONArray a=o.getJSONArray("sources");if(a!=null)for(Object v:a)p.sources.add(clean(String.valueOf(v)));return p;}
    }
}
