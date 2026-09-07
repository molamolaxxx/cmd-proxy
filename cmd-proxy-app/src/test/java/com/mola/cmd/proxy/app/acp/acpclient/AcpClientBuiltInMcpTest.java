package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.action.CmdProxyMcpHttpHandler;
import org.junit.Test;

import static org.junit.Assert.*;

public class AcpClientBuiltInMcpTest {

    @Test
    public void alwaysAppendsReservedRuntimeServerWithHeaders() {
        JsonArray servers = new JsonArray();
        AcpClient.appendBuiltInMcpServer(servers, "http://127.0.0.1:12345", "auth-1");
        assertEquals(1, servers.size());
        JsonObject server = servers.get(0).getAsJsonObject();
        assertEquals(CmdProxyMcpHttpHandler.SERVER_NAME, server.get("name").getAsString());
        assertEquals("http://127.0.0.1:12345/mcp", server.get("url").getAsString());
        assertEquals(1, server.getAsJsonArray("headers").size());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsUserServerUsingReservedRuntimeName() {
        JsonArray servers = new JsonArray();
        JsonObject configured = new JsonObject();
        configured.addProperty("name", CmdProxyMcpHttpHandler.SERVER_NAME);
        servers.add(configured);
        AcpClient.appendBuiltInMcpServer(servers, "http://127.0.0.1:12345", "auth-1");
    }

    @Test(expected = IllegalStateException.class)
    public void refusesSessionWhenControlServerIsUnavailable() {
        AcpClient.appendBuiltInMcpServer(new JsonArray(), "", "auth-1");
    }

    @Test
    public void appendsRuntimeTaskServerWithoutMutatingCallerSnapshot() {
        JsonArray configured = new JsonArray();
        JsonArray additions = new JsonArray();
        JsonObject task = new JsonObject();
        task.addProperty("name", "starweave-tasks");
        task.addProperty("type", "http");
        task.addProperty("url", "http://127.0.0.1:12346/task-mcp/sse");
        additions.add(task);

        AcpClient.appendAdditionalMcpServers(configured, additions);
        task.addProperty("url", "mutated");

        assertEquals("http://127.0.0.1:12346/task-mcp/sse",
                configured.get(0).getAsJsonObject().get("url").getAsString());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsAdditionalServerNameCollision() {
        JsonArray configured = new JsonArray();
        JsonObject first = new JsonObject();
        first.addProperty("name", "starweave-tasks");
        configured.add(first);
        JsonArray additions = new JsonArray();
        additions.add(first.deepCopy());
        AcpClient.appendAdditionalMcpServers(configured, additions);
    }
}
