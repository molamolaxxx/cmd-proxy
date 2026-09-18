package com.mola.cmd.proxy.app.acp.talkto;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry;
import com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToBatchMessage;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToMessage;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToTrace;
import com.mola.cmd.proxy.app.acp.acpclient.PromptOptions;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public class TalkToInboxBatchTest {

    @Test
    public void drainsCompatibleBurstAsOneBatchAndPreservesFifo() {
        TalkToDispatcher dispatcher = dispatcher();
        TalkToTrace root = TalkToTrace.root();
        for (int i = 0; i < 8; i++) {
            assertTrue(dispatcher.offerToInbox("route",
                    new TalkToMessage("sender", "message-" + i, 1)));
        }

        TalkToMessage drained = dispatcher.pollInboxBatch("route", "unused", 8);

        assertTrue(drained instanceof TalkToBatchMessage);
        TalkToBatchMessage batch = (TalkToBatchMessage) drained;
        assertEquals(8, batch.getMessages().size());
        assertEquals("message-0", batch.getMessages().get(0).getContent());
        assertEquals("message-7", batch.getMessages().get(7).getContent());
        assertTrue(batch.buildPrompt().contains("已合并为一个 turn"));
        assertNull(dispatcher.pollInbox("route"));
    }

    @Test
    public void doesNotCrossSenderOrPrincipalBoundary() {
        TalkToDispatcher dispatcher = dispatcher();
        AuthPrincipalContext firstPrincipal =
                new AuthPrincipalContext("one", "One", "TEST", "source");
        AuthPrincipalContext secondPrincipal =
                new AuthPrincipalContext("two", "Two", "TEST", "source");
        dispatcher.offerToInbox("route", new TalkToMessage(
                "sender", "first", 1, Collections.emptyList(), firstPrincipal));
        dispatcher.offerToInbox("route", new TalkToMessage(
                "sender", "second", 1, Collections.emptyList(), secondPrincipal));
        dispatcher.offerToInbox("route", new TalkToMessage(
                "other", "third", 1, Collections.emptyList(), secondPrincipal));

        assertEquals("first", dispatcher.pollInboxBatch("route", "unused", 8).getContent());
        assertEquals("second", dispatcher.pollInboxBatch("route", "unused", 8).getContent());
        assertEquals("third", dispatcher.pollInboxBatch("route", "unused", 8).getContent());
    }

    @Test
    public void nonBatchableMessageRemainsIsolated() {
        TalkToDispatcher dispatcher = dispatcher();
        dispatcher.offerToInbox("route", new TalkToMessage("sender", "external", 1) {
            @Override public boolean isBatchable() { return false; }
        });
        dispatcher.offerToInbox("route", new TalkToMessage("sender", "internal", 1));

        assertEquals("external",
                dispatcher.pollInboxBatch("route", "unused", 8).getContent());
        assertEquals("internal",
                dispatcher.pollInboxBatch("route", "unused", 8).getContent());
    }

    @Test
    public void distinctCascadesRetainBothBudgetsWhenBatched() {
        TalkToDispatcher dispatcher = dispatcher();
        dispatcher.offerToInbox("route", new TalkToMessage("sender", "first", 1));
        dispatcher.offerToInbox("route", new TalkToMessage("sender", "second", 1));
        TalkToMessage batch = dispatcher.pollInboxBatch("route", null, 8);
        assertTrue(batch instanceof TalkToBatchMessage);
        assertEquals(2, batch.getTrace().getCascadeIds().size());
        assertNull(dispatcher.pollInbox("route"));
    }

    @Test
    public void forwardingAndFanoutShareTheWholeTurnCascade() {
        PromptOptions options = PromptOptions.defaults();
        TalkToTrace root = options.talkToParentFor("A");
        assertSame(root, options.talkToParentFor("B"));
        TalkToTrace incoming = root.next();
        PromptOptions inbound = PromptOptions.defaults().addTalkToParent("A", incoming);
        assertSame(incoming, inbound.talkToParentFor("C"));
    }

    @Test
    public void openedCascadeDropsQueuedWorkWithoutProducingAnotherBatch() {
        TalkToDispatcher dispatcher = dispatcher();
        TalkToCircuitBreaker.Admission first = dispatcher.circuitBreaker.admit(null, "A", "B");
        dispatcher.offerToInbox("route", new TalkToMessage("A", "queued", 1,
                Collections.emptyList(), null, first.getTrace()));
        assertFalse(dispatcher.circuitBreaker.openCascade(
                first.getTrace(), "TEST_LIMIT").isAccepted());
        assertNull(dispatcher.pollInboxBatch("route", null, 8));
    }

    private static TalkToDispatcher dispatcher() {
        return new TalkToDispatcher(Collections.emptyMap(),
                AcpClientRegistry.getInstance(), Collections.emptyMap());
    }
}
