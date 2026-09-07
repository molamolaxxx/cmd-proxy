package com.mola.cmd.proxy.app.acp.talkto;

import com.mola.cmd.proxy.app.acp.talkto.model.TalkToTrace;
import org.junit.Test;

import static org.junit.Assert.*;

public class TalkToCircuitBreakerTest {

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsafeExternallySuppliedTraceIdentifiers() {
        new TalkToTrace("../../other", "message", null, 1,
                System.currentTimeMillis());
    }

    @Test
    public void serverOwnedHopLimitStopsChangingContentCascade() {
        TalkToCircuitBreaker breaker = new TalkToCircuitBreaker();
        TalkToTrace parent = null;
        for (int hop = 1; hop <= TalkToCircuitBreaker.MAX_HOPS; hop++) {
            TalkToCircuitBreaker.Admission admitted = breaker.admit(
                    parent, "sender-" + hop, "target-" + hop);
            assertTrue(admitted.isAccepted());
            assertEquals(hop, admitted.getTrace().getHopCount());
            parent = admitted.getTrace();
        }

        TalkToCircuitBreaker.Admission rejected = breaker.admit(
                parent, "sender-6", "target-6");

        assertFalse(rejected.isAccepted());
        assertEquals("HOP_LIMIT", rejected.getReason());
        assertEquals(6, rejected.getTrace().getHopCount());
    }

    @Test
    public void perSenderLimitStopsAcknowledgementPingPongBeforeHopLimit() {
        TalkToCircuitBreaker breaker = new TalkToCircuitBreaker();
        TalkToCircuitBreaker.Admission first = breaker.admit(null, "A", "B");
        TalkToCircuitBreaker.Admission second = breaker.admit(first.getTrace(), "A", "C");
        TalkToCircuitBreaker.Admission rejected = breaker.admit(second.getTrace(), "A", "D");

        assertTrue(first.isAccepted());
        assertTrue(second.isAccepted());
        assertFalse(rejected.isAccepted());
        assertEquals("SENDER_LIMIT", rejected.getReason());
    }

    @Test
    public void fanoutCannotResetBudgetByChangingRecipientsAndNotifiesOnce() {
        TalkToCircuitBreaker breaker = new TalkToCircuitBreaker();
        TalkToTrace parent = new TalkToTrace(null, null, null, 0, System.currentTimeMillis());
        for (int i = 0; i < 6; i++) {
            assertEquals(i < 2, breaker.admit(parent, "leader", "member-" + i).isAccepted());
        }
        assertTrue(breaker.claimNotification(parent.getCascadeId()));
        assertFalse(breaker.claimNotification(parent.getCascadeId()));
        assertFalse(breaker.canDeliver(parent.next()));
    }

    @Test
    public void totalBudgetIsSharedByBranches() {
        TalkToCircuitBreaker breaker = new TalkToCircuitBreaker();
        TalkToTrace parent = new TalkToTrace(null, null, null, 0, System.currentTimeMillis());
        for (int i = 0; i < 12; i++) assertTrue(breaker.admit(parent, "s-" + i, "t-" + i).isAccepted());
        assertEquals("MESSAGE_LIMIT", breaker.admit(parent, "s-12", "t-12").getReason());
    }

    @Test
    public void expiredTraceCannotBeDeliveredEvenAfterStateCleanup() {
        TalkToCircuitBreaker breaker = new TalkToCircuitBreaker();
        TalkToTrace expired = new TalkToTrace(null, null, null, 2,
                System.currentTimeMillis() - TalkToCircuitBreaker.CASCADE_TTL_MS * 3);
        assertFalse(breaker.canDeliver(expired));
        assertEquals("CASCADE_EXPIRED", breaker.admit(expired, "A", "B").getReason());
    }

    @Test
    public void failedQueueAdmissionCanRollBackBudget() {
        TalkToCircuitBreaker breaker = new TalkToCircuitBreaker();
        TalkToCircuitBreaker.Admission first = breaker.admit(null, "A", "B");
        breaker.rollback(first, "A", "B");

        assertEquals(0, breaker.activeCascadeCount());
    }
}
