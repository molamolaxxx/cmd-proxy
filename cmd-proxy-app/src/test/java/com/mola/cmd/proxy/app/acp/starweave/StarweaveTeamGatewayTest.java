package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.client.resp.CmdResponseContent;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StarweaveTeamGatewayTest {

    @Test
    public void readyHandshakeMustMatchTransportDerivedIdentity() {
        StarweaveTeamGateway gateway = new StarweaveTeamGateway(
                "instance-b", "team-acp-instance-b");
        JSONObject valid = new JSONObject(true);
        valid.put("instanceId", "instance-b");
        valid.put("ownerChatterId", "starweave-instance-b");

        assertEquals("true", gateway.acceptReady("ready-1",
                new String[]{valid.toJSONString()}).get("accepted"));

        valid.put("ownerChatterId", "starweave-instance-a");
        try {
            gateway.acceptReady("ready-forged", new String[]{valid.toJSONString()});
            fail("forged ready owner must be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("Starweave Team gateway identity mismatch", expected.getMessage());
        }
    }

    @Test
    public void requestUsesAuthenticatedIdentityAndCompletesOnlyMatchingResult() {
        final StarweaveTeamGateway[] holder = new StarweaveTeamGateway[1];
        holder[0] = gateway((command, group, request) -> {
            assertEquals(StarweaveTeamGateway.REQUEST_COMMAND, command);
            assertEquals("team-acp-instance-b", group);
            assertEquals(request.getCmdId(), request.getResultMap().get("requestId"));
            assertEquals("instance-b", request.getResultMap().get("instanceId"));
            assertEquals("starweave-instance-b",
                    request.getResultMap().get("ownerChatterId"));
            assertEquals("sources", request.getResultMap().get("operation"));

            JSONObject unknown = accepted("other-request");
            assertEquals("false", holder[0].acceptResult("rpc-unknown",
                    new String[]{unknown.toJSONString()}).get("accepted"));

            JSONObject response = accepted(request.getCmdId());
            JSONObject data = new JSONObject(true);
            data.put("marker", "coordinated");
            response.put("data", data);
            assertEquals("true", holder[0].acceptResult("rpc-result",
                    new String[]{response.toJSONString()}).get("accepted"));
        });
        ready(holder[0]);

        JSONObject result = holder[0].query("sources", new JSONObject(true));

        assertEquals("coordinated", result.getString("marker"));
    }

    @Test
    public void rejectedRequestKeepsCoordinatorAvailable() {
        AtomicInteger calls = new AtomicInteger();
        final StarweaveTeamGateway[] holder = new StarweaveTeamGateway[1];
        holder[0] = gateway((command, group, request) -> {
            JSONObject response = accepted(request.getCmdId());
            if (calls.getAndIncrement() == 0) {
                response.put("accepted", false);
                response.put("code", "UNAUTHORIZED");
                response.put("message", "rejected by coordinator");
            }
            holder[0].acceptResult("rpc-result",
                    new String[]{response.toJSONString()});
        });
        ready(holder[0]);

        try {
            holder[0].query("sources", new JSONObject(true));
            fail("coordinator rejection must be surfaced");
        } catch (IllegalStateException expected) {
            assertEquals("rejected by coordinator", expected.getMessage());
        }
        holder[0].query("sources", new JSONObject(true));
        assertEquals(2, calls.get());
    }

    @Test
    public void timeoutFailsClosedUntilAReplacementReadyHandshakeArrives() {
        AtomicInteger calls = new AtomicInteger();
        final StarweaveTeamGateway[] holder = new StarweaveTeamGateway[1];
        holder[0] = new StarweaveTeamGateway("instance-b", "team-acp-instance-b",
                20L, 20L, (command, group, request) -> {
            if (calls.incrementAndGet() == 2) {
                holder[0].acceptResult("rpc-after-ready", new String[]{
                        accepted(request.getCmdId()).toJSONString()});
            }
        });
        ready(holder[0]);

        try {
            holder[0].query("sources", new JSONObject(true));
            fail("missing result must time out");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("unavailable"));
        }
        try {
            holder[0].query("sources", new JSONObject(true));
            fail("timed-out gateway must stay unavailable");
        } catch (IllegalStateException expected) {
            assertEquals("Starweave Team coordinator is unavailable",
                    expected.getMessage());
        }
        assertEquals(1, calls.get());

        ready(holder[0]);
        holder[0].query("sources", new JSONObject(true));
        assertEquals(2, calls.get());
    }

    private static StarweaveTeamGateway gateway(
            StarweaveTeamGateway.CallbackSender sender) {
        return new StarweaveTeamGateway("instance-b", "team-acp-instance-b",
                200L, 200L, sender);
    }

    private static void ready(StarweaveTeamGateway gateway) {
        JSONObject ready = new JSONObject(true);
        ready.put("instanceId", "instance-b");
        ready.put("ownerChatterId", "starweave-instance-b");
        gateway.acceptReady("ready", new String[]{ready.toJSONString()});
    }

    private static JSONObject accepted(String requestId) {
        JSONObject response = new JSONObject(true);
        response.put("requestId", requestId);
        response.put("accepted", true);
        response.put("code", "OK");
        response.put("message", "OK");
        response.put("data", new JSONObject(true));
        return response;
    }
}
