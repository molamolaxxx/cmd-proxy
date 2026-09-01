package com.mola.cmd.proxy.app.acp.starweave;

import com.alibaba.fastjson.JSONObject;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class StarweaveRequestDeduplicatorTest {

    @Test
    public void duplicateRequestReturnsFirstResultWithoutRepeatingMutation()
            throws Exception {
        StarweaveRequestDeduplicator deduplicator =
                new StarweaveRequestDeduplicator(4, 60_000L);
        AtomicInteger calls = new AtomicInteger();

        JSONObject first = deduplicator.execute("request-1", "new:session-1", () -> {
            JSONObject value = new JSONObject(true);
            value.put("call", calls.incrementAndGet());
            return value;
        });
        JSONObject duplicate = deduplicator.execute(
                "request-1", "new:session-1", () -> {
                    throw new AssertionError("duplicate mutation executed");
                });

        assertEquals(1, first.getIntValue("call"));
        assertEquals(1, duplicate.getIntValue("call"));
        assertEquals(1, calls.get());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRequestIdReuseForDifferentCommand() throws Exception {
        StarweaveRequestDeduplicator deduplicator =
                new StarweaveRequestDeduplicator(4, 60_000L);
        deduplicator.execute("request-1", "new:session-1", JSONObject::new);
        deduplicator.execute("request-1", "delete:session-1", JSONObject::new);
    }
}
