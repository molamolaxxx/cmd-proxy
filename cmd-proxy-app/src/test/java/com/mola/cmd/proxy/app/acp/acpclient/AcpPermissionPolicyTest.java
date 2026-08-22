package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProvider;
import com.mola.cmd.proxy.app.acp.acpclient.agent.KiroCliAgentProvider;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AcpPermissionPolicyTest {

    @Test
    public void selectsOnlyOptionsAllowedByPolicy() {
        TestClient client = new TestClient();
        JsonArray options = options();

        assertEquals("reject", client.selectPermissionOption(
                options, AgentProvider.PermissionPolicy.REJECT));
        assertEquals("once", client.selectPermissionOption(
                options, AgentProvider.PermissionPolicy.ALLOW_ONCE));
        assertEquals("always", client.selectPermissionOption(
                options, AgentProvider.PermissionPolicy.ALLOW_ALWAYS));
    }

    @Test
    public void failsClosedWhenRequestedOptionIsUnavailable() {
        TestClient client = new TestClient();
        JsonArray options = new JsonArray();
        options.add(option("allow_once", "once"));

        assertNull(client.selectPermissionOption(
                options, AgentProvider.PermissionPolicy.REJECT));
    }

    private JsonArray options() {
        JsonArray options = new JsonArray();
        options.add(option("allow_once", "once"));
        options.add(option("allow_always", "always"));
        options.add(option("reject_once", "reject"));
        return options;
    }

    private JsonObject option(String kind, String id) {
        JsonObject option = new JsonObject();
        option.addProperty("kind", kind);
        option.addProperty("optionId", id);
        return option;
    }

    private static final class TestClient extends AbstractAcpClient {
        private TestClient() {
            super(new KiroCliAgentProvider(), ".", "permission-test");
        }

        @Override
        protected void createSession() {
        }
    }
}
