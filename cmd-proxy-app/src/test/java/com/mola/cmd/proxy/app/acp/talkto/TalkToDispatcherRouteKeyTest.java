package com.mola.cmd.proxy.app.acp.talkto;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TalkToDispatcherRouteKeyTest {

    @Test
    public void molaChatLocalRouteIncludesExactChatterId() {
        AcpClientIdentity identity = AcpClientIdentity.main(
                "group-a", "Robot A", "Robot A");

        assertEquals("MOLACHAT:chatter-a:Robot B",
                TalkToDispatcher.localRouteKey(identity, "chatter-a", "Robot B"));
        assertEquals("MOLACHAT:chatter-b:Robot B",
                TalkToDispatcher.localRouteKey(identity, "chatter-b", "Robot B"));
    }

    @Test
    public void starweaveLocalRouteUsesStructuredOwnerInsteadOfMolaChatFallback() {
        AcpClientIdentity identity = AcpClientIdentity.starweave(
                "group-sw", "transport-sw", "starweave/robot-a",
                "starweave-instance-a", "Robot A");

        assertEquals("STARWEAVE:starweave-instance-a:Robot B",
                TalkToDispatcher.localRouteKey(identity, "mola-chat-user", "Robot B"));
    }

    @Test
    public void exactLocalResolutionNeverFallsBackToAnotherChatterOrSurface() {
        AcpClientIdentity sender = AcpClientIdentity.main(
                "sender-group", "Robot A", "Robot A");
        Map<String, String> routes = new HashMap<>();
        routes.put("Robot B", "legacy-first-group");
        routes.put("MOLACHAT:chatter-b:Robot B", "mola-b-group");
        routes.put("STARWEAVE:starweave-instance-a:Robot B", "starweave-group");

        assertNull(TalkToDispatcher.resolveLocalTargetGroupId(
                routes, sender, "chatter-a", "Robot B"));
        assertEquals("mola-b-group", TalkToDispatcher.resolveLocalTargetGroupId(
                routes, sender, "chatter-b", "Robot B"));
    }
}
