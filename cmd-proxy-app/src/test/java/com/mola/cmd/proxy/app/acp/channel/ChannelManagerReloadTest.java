package com.mola.cmd.proxy.app.acp.channel;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelBinding;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelConfig;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelReplyRoute;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelSendResult;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelStatus;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelManagerReloadTest {

    @Test
    public void reloadOnlyRestartsSelectedChannel() {
        RecordingFactory factory = new RecordingFactory();
        ChannelManager manager = manager(factory, config("channel-a", "bot-a"),
                config("channel-b", "bot-b"));
        manager.start();
        RecordingAdapter firstA = factory.latest.get("channel-a");
        RecordingAdapter firstB = factory.latest.get("channel-b");

        ChannelConfig changedA = config("channel-a", "bot-a-new");
        manager.reloadChannel("channel-a", changedA);

        RecordingAdapter secondA = factory.latest.get("channel-a");
        assertEquals(1, firstA.stops.get());
        assertEquals(1, secondA.starts.get());
        assertSame(firstB, factory.latest.get("channel-b"));
        assertEquals(0, firstB.stops.get());
        assertEquals(ChannelStatus.CONNECTED, manager.getStatuses().get("channel-a"));
        assertEquals(ChannelStatus.CONNECTED, manager.getStatuses().get("channel-b"));
        manager.close();
    }

    @Test
    public void renameStopsOldIdAndStartsNewId() {
        RecordingFactory factory = new RecordingFactory();
        ChannelManager manager = manager(factory, config("channel-old", "bot-a"));
        manager.start();
        RecordingAdapter old = factory.latest.get("channel-old");

        manager.reloadChannel("channel-old", config("channel-new", "bot-a"));

        assertEquals(1, old.stops.get());
        assertFalse(manager.getStatuses().containsKey("channel-old"));
        assertEquals(ChannelStatus.CONNECTED, manager.getStatuses().get("channel-new"));
        assertTrue(factory.latest.containsKey("channel-new"));
        manager.close();
    }

    @Test
    public void invalidReplacementLeavesRunningChannelUntouched() {
        RecordingFactory factory = new RecordingFactory();
        ChannelManager manager = manager(factory, config("channel-a", "bot-a"));
        manager.start();
        RecordingAdapter running = factory.latest.get("channel-a");
        ChannelConfig invalid = config("channel-a", "bot-a");
        invalid.setSecret("");

        try {
            manager.reloadChannel("channel-a", invalid);
            fail("expected invalid replacement to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("secret"));
        }

        assertEquals(0, running.stops.get());
        assertEquals(ChannelStatus.CONNECTED, manager.getStatuses().get("channel-a"));
        manager.close();
    }

    @Test
    public void renameCannotReplaceAnotherRunningChannel() {
        RecordingFactory factory = new RecordingFactory();
        ChannelManager manager = manager(factory, config("channel-a", "bot-a"),
                config("channel-b", "bot-b"));
        manager.start();
        RecordingAdapter runningA = factory.latest.get("channel-a");
        RecordingAdapter runningB = factory.latest.get("channel-b");

        try {
            manager.reloadChannel("channel-a", config("channel-b", "bot-new"));
            fail("expected duplicate id to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("duplicate channel.id"));
        }

        assertEquals(0, runningA.stops.get());
        assertEquals(0, runningB.stops.get());
        assertEquals(ChannelStatus.CONNECTED, manager.getStatuses().get("channel-a"));
        assertEquals(ChannelStatus.CONNECTED, manager.getStatuses().get("channel-b"));
        manager.close();
    }

    private static ChannelManager manager(RecordingFactory factory, ChannelConfig... configs) {
        TalkToDispatcher dispatcher = new TalkToDispatcher(Collections.emptyMap(),
                AcpClientRegistry.getInstance(), Collections.emptyMap());
        return new ChannelManager(Arrays.asList(configs), "instance",
                AcpClientRegistry.getInstance(), dispatcher, factory);
    }

    private static ChannelConfig config(String id, String botId) {
        ChannelBinding binding = new ChannelBinding();
        binding.setType(ChannelBinding.TYPE_TEAM_MEMBER);
        binding.setInstanceId("instance");
        binding.setTeamId("team-1");
        binding.setTeamMemberId("member-1");
        ChannelConfig config = new ChannelConfig();
        config.setId(id);
        config.setType(ChannelConfig.TYPE_WECOM_WS);
        config.setEnabled(true);
        config.setBotId(botId);
        config.setSecret("secret");
        config.setBinding(binding);
        return config;
    }

    private static final class RecordingFactory implements ChannelManager.AdapterFactory {
        private final Map<String, RecordingAdapter> latest = new HashMap<>();

        @Override
        public ChannelAdapter create(ChannelConfig config, ChannelTalkToBridge bridge) {
            RecordingAdapter adapter = new RecordingAdapter(config.getId());
            latest.put(config.getId(), adapter);
            return adapter;
        }
    }

    private static final class RecordingAdapter implements ChannelAdapter {
        private final String id;
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger stops = new AtomicInteger();

        private RecordingAdapter(String id) { this.id = id; }
        @Override public String getChannelId() { return id; }
        @Override public ChannelStatus getStatus() { return ChannelStatus.CONNECTED; }
        @Override public void start() { starts.incrementAndGet(); }
        @Override public void stop() { stops.incrementAndGet(); }
        @Override public ChannelSendResult send(ChannelReplyRoute route, String markdown) {
            return ChannelSendResult.success("passive");
        }
        @Override public ChannelSendResult sendProactive(String chatId, String markdown) {
            return ChannelSendResult.success("proactive");
        }
    }
}
