package com.mola.cmd.proxy.app.acp.acpclient.context;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class ConversationHistoryManagerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void teamIdentityUsesHierarchicalNamespaceBelowSessionRoot() {
        AcpClientIdentity identity = teamIdentity("team/team-1/member-1");
        ConversationHistoryManager manager = new ConversationHistoryManager(identity);

        Path path = manager.getSessionBaseDir();
        assertTrue(path.endsWith("team/team-1/member-1"));
        assertTrue(path.isAbsolute());
    }

    @Test
    public void mainIdentityKeepsLegacySingleDirectorySanitization() {
        AcpClientIdentity identity =
                AcpClientIdentity.main("group-1", "Robot/One", "Robot/One");
        ConversationHistoryManager manager = new ConversationHistoryManager(identity);

        assertTrue(manager.getSessionBaseDir().endsWith("Robot-One"));
        assertFalse(manager.getSessionBaseDir().endsWith("Robot/One"));
    }

    @Test
    public void mainIdentitySupportsPureUnicodeRobotName() {
        AcpClientIdentity identity =
                AcpClientIdentity.main("group-1", "结算专家", "结算专家");
        ConversationHistoryManager manager = new ConversationHistoryManager(identity);

        assertTrue(manager.getSessionBaseDir().getFileName().toString()
                .startsWith("unicode-"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void teamIdentityRejectsParentTraversal() {
        new ConversationHistoryManager(teamIdentity("team/team-1/../member-1"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void teamIdentityRejectsAbsolutePath() {
        new ConversationHistoryManager(teamIdentity("/tmp/team/member-1"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void teamIdentityRejectsWindowsAbsolutePath() {
        new ConversationHistoryManager(teamIdentity("C:\\temp\\team\\member-1"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void teamIdentityRejectsEmptySegment() {
        new ConversationHistoryManager(teamIdentity("team//member-1"));
    }

    @Test
    public void pendingMemoryMarkerIsVersionedAndClearedOnlyByCompletedVersion()
            throws Exception {
        ConversationHistoryManager manager = new ConversationHistoryManager(
                AcpClientIdentity.main("group-1", "Robot One", "Robot One"),
                temporaryFolder.newFolder("sessions").toPath());
        manager.addUserMessage("first");
        manager.addAssistantMessage("answer");
        manager.flushTurn("session-1");

        assertTrue(manager.markMemoryExtractionPending("session-1", 1));
        assertTrue(manager.markMemoryExtractionPending("session-1", 2));
        assertTrue(manager.markMemoryExtractionPending("session-1", 1));

        List<ConversationHistoryManager.PendingMemoryExtraction> pending =
                manager.listPendingMemoryExtractions();
        assertEquals(1, pending.size());
        assertEquals("session-1", pending.get(0).getSessionId());
        assertEquals(2, pending.get(0).getTurnCount());
        assertFalse(manager.clearMemoryExtractionPending("session-1", 1));
        assertEquals(1, manager.listPendingMemoryExtractions().size());
        assertTrue(manager.clearMemoryExtractionPending("session-1", 2));
        assertTrue(manager.listPendingMemoryExtractions().isEmpty());
    }

    @Test
    public void lastMessageTimeExistsOnlyAfterATurnIsPersisted() throws Exception {
        ConversationHistoryManager manager = new ConversationHistoryManager(
                AcpClientIdentity.main("group-2", "Robot Two", "Robot Two"),
                temporaryFolder.newFolder("activity-sessions").toPath());

        assertEquals(0L, manager.getLastMessageAt("session-2"));
        manager.addUserMessage("hello");
        manager.addAssistantMessage("world");
        manager.flushTurn("session-2");

        assertTrue(manager.getLastMessageAt("session-2") > 0L);
        assertEquals(0L, manager.getLastMessageAt("missing-session"));
    }

    private static AcpClientIdentity teamIdentity(String namespace) {
        return AcpClientIdentity.team(
                "team-acp-member-1",
                "team-acp-instance-1",
                namespace,
                "chatter-1",
                "team-1",
                "member-1",
                "Source Robot");
    }
}
