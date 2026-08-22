package com.mola.cmd.proxy.app.acp.mcpauth;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class McpAuthManagerTest {

    @Test
    public void protectedServerUsesCurrentWeComUserAndExactTurnCleanup() throws Exception {
        Path dir = Files.createTempDirectory("mcp-auth-test");
        McpAuthManager manager = new McpAuthManager(dir.resolve("config.json"));
        String session = manager.createSession("client-1");

        JSONObject registration = new JSONObject();
        registration.put("authSessionId", session);
        registration.put("serverId", "server-1");
        registration.put("name", "Server One");
        registration.put("tools", new JSONArray());
        assertTrue(manager.register(registration).getBooleanValue("success"));

        JSONObject policy = new JSONObject();
        policy.put("serverId", "server-1");
        policy.put("authEnabled", true);
        JSONArray allowed = new JSONArray();
        allowed.add("user-a");
        policy.put("allowedPrincipalIds", allowed);
        assertTrue(manager.updatePolicy(policy).getBooleanValue("success"));

        JSONObject check = new JSONObject();
        check.put("authSessionId", session);
        check.put("serverId", "server-1");
        JSONObject noWeComUser = manager.check(check);
        assertTrue(noWeComUser.getBooleanValue("allowed"));
        assertEquals("NO_PRINCIPAL_ALLOWED", noWeComUser.getString("code"));

        AuthPrincipalContext first = new AuthPrincipalContext(
                "user-a", "Alice", "WECOM", "wecom-main");
        manager.bind(session, first, "turn-a");
        JSONObject allowedResult = manager.check(check);
        assertTrue(allowedResult.getBooleanValue("allowed"));
        assertEquals("user-a", allowedResult.getString("principalId"));

        AuthPrincipalContext second = new AuthPrincipalContext(
                "user-b", "Bob", "WECOM", "wecom-main");
        manager.bind(session, second, "turn-b");
        manager.unbind(session, "turn-a");
        JSONObject denied = manager.check(check);
        assertFalse(denied.getBooleanValue("allowed"));
        assertEquals("PRINCIPAL_NOT_ALLOWED", denied.getString("code"));
        assertEquals("user-b", denied.getString("principalId"));

        manager.unbind(session, "turn-b");
        JSONObject afterWeComTurn = manager.check(check);
        assertTrue(afterWeComTurn.getBooleanValue("allowed"));
        assertEquals("NO_PRINCIPAL_ALLOWED", afterWeComTurn.getString("code"));
    }

    @Test
    public void unprotectedServerAllowsWithoutChannelIdentity() throws Exception {
        McpAuthManager manager = new McpAuthManager(
                Files.createTempDirectory("mcp-auth-open").resolve("config.json"));
        String session = manager.createSession("client-1");
        JSONObject registration = new JSONObject();
        registration.put("authSessionId", session);
        registration.put("serverId", "open-server");
        registration.put("name", "Open Server");
        registration.put("tools", new JSONArray());
        manager.register(registration);

        JSONObject check = new JSONObject();
        check.put("authSessionId", session);
        check.put("serverId", "open-server");
        JSONObject result = manager.check(check);
        assertTrue(result.getBooleanValue("allowed"));
        assertEquals("AUTH_DISABLED", result.getString("code"));
    }
}
