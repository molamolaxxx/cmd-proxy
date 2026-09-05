package com.mola.cmd.proxy.app.acp.channel;

import com.mola.cmd.proxy.app.acp.channel.model.ChannelEvent;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelReplyRoute;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ChannelAffinityStoreTest {

    @Test
    public void claimsFreeMembersBeforeDeterministicHashFallbackAndSurvivesReload()
            throws Exception {
        Path path = Files.createTempDirectory("channel-affinity-test")
                .resolve("affinity.json");
        ChannelAffinityStore store = new ChannelAffinityStore(path);
        List<String> members = Arrays.asList("member-a", "member-b", "member-c");
        String firstKey = "SINGLE:user-1";
        String firstChoice = ChannelAffinityStore.rendezvousOrder(firstKey, members).get(0);
        String collidingKey = findSameFirstChoice(firstChoice, members);

        ChannelAffinityStore.Selection first = store.select(
                "instance", "channel", "team", firstKey, members, members);
        ChannelAffinityStore.Selection second = store.select(
                "instance", "channel", "team", collidingKey, members, members);
        ChannelAffinityStore.Selection third = store.select(
                "instance", "channel", "team", "GROUP:chat-3", members, members);
        ChannelAffinityStore.Selection fallback = store.select(
                "instance", "channel", "team", "SINGLE:user-4", members, members);

        assertEquals(ChannelAffinityStore.SelectionReason.FREE_SLOT, first.getReason());
        assertEquals(firstChoice, first.getTeamMemberId());
        assertEquals(ChannelAffinityStore.SelectionReason.FREE_SLOT, second.getReason());
        assertNotEquals(first.getTeamMemberId(), second.getTeamMemberId());
        assertEquals(ChannelAffinityStore.SelectionReason.FREE_SLOT, third.getReason());
        assertEquals(3, new java.util.HashSet<>(Arrays.asList(first.getTeamMemberId(),
                second.getTeamMemberId(), third.getTeamMemberId())).size());
        assertEquals(ChannelAffinityStore.SelectionReason.HASH_FALLBACK, fallback.getReason());
        assertEquals(ChannelAffinityStore.rendezvousOrder("SINGLE:user-4", members).get(0),
                fallback.getTeamMemberId());

        ChannelAffinityStore.Selection restored = new ChannelAffinityStore(path).select(
                "instance", "channel", "team", firstKey, members, members);
        assertEquals(ChannelAffinityStore.SelectionReason.EXISTING_CLAIM,
                restored.getReason());
        assertEquals(first.getTeamMemberId(), restored.getTeamMemberId());
        String persisted = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertFalse(persisted.contains("user-1"));
        assertFalse(persisted.contains("chat-3"));
    }

    @Test
    public void groupUsesChatIdAndSingleUsesUserId() {
        ChannelEvent groupA = event("group", "same-chat", "user-a");
        ChannelEvent groupB = event("group", "same-chat", "user-b");
        ChannelEvent single = event("single", "ignored-chat", "user-a");

        assertEquals("GROUP:same-chat", DefaultChannelBindingResolver.affinityRoutingKey(groupA));
        assertEquals(DefaultChannelBindingResolver.affinityRoutingKey(groupA),
                DefaultChannelBindingResolver.affinityRoutingKey(groupB));
        assertEquals("SINGLE:user-a",
                DefaultChannelBindingResolver.affinityRoutingKey(single));
    }

    @Test
    public void clearingOneScopeDoesNotRemoveAnotherChannelScope() throws Exception {
        Path path = Files.createTempDirectory("channel-affinity-clear")
                .resolve("affinity.json");
        ChannelAffinityStore store = new ChannelAffinityStore(path);
        List<String> members = Arrays.asList("member-a", "member-b");
        store.select("instance", "channel-a", "team", "SINGLE:user-a",
                members, members);
        store.select("instance", "channel-b", "team", "SINGLE:user-b",
                members, members);

        store.clearScope("instance", "channel-a", "team");

        assertEquals(ChannelAffinityStore.SelectionReason.FREE_SLOT,
                store.select("instance", "channel-a", "team", "SINGLE:user-a",
                        members, members).getReason());
        assertEquals(ChannelAffinityStore.SelectionReason.EXISTING_CLAIM,
                store.select("instance", "channel-b", "team", "SINGLE:user-b",
                        members, members).getReason());
    }

    private static String findSameFirstChoice(String choice, List<String> members) {
        for (int i = 2; i < 10_000; i++) {
            String key = "SINGLE:collision-" + i;
            if (choice.equals(ChannelAffinityStore.rendezvousOrder(key, members).get(0))) {
                return key;
            }
        }
        throw new AssertionError("failed to find deterministic test collision");
    }

    private static ChannelEvent event(String chatType, String chatId, String userId) {
        return new ChannelEvent("channel", "message-" + userId, userId, "sender", "hello",
                new ChannelReplyRoute("request", "message", userId, chatId, chatType,
                        System.currentTimeMillis() + 60_000));
    }
}
