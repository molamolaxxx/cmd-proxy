package com.mola.cmd.proxy.app.acp.channel;

import com.alibaba.fastjson.JSON;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelBinding;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelConfig;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelEvent;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelReplyRoute;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ChannelInboundSwitchTest {

    @Test
    public void inboundDefaultsToEnabled() {
        assertTrue(new ChannelConfig().isInboundEnabled());
        assertTrue(JSON.parseObject("{\"id\":\"legacy-channel\"}", ChannelConfig.class)
                .isInboundEnabled());
    }

    @Test
    public void disabledInboundStopsBeforeRouteCreationAndClientResolution() {
        ChannelConfig config = config();
        Map<String, ChannelConfig> configs = new HashMap<>();
        configs.put(config.getId(), config);
        ChannelTalkToGateway gateway = new ChannelTalkToGateway(
                Collections.emptyMap(), configs);
        AtomicInteger resolutions = new AtomicInteger();
        ChannelTalkToBridge bridge = new ChannelTalkToBridge(
                configs,
                groupId -> {
                    resolutions.incrementAndGet();
                    return null;
                },
                new TalkToDispatcher(Collections.emptyMap(),
                        com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry.getInstance(),
                        Collections.emptyMap()),
                gateway);

        assertTrue(bridge.setInboundEnabled("wecom-main", false));
        TalkToDispatcher.InboundDeliveryResult result = bridge.onEvent(event());

        assertEquals(TalkToDispatcher.InboundDeliveryResult.Status.REJECTED,
                result.getStatus());
        assertEquals("channel inbound disabled", result.getReason());
        assertEquals(0, resolutions.get());
        assertEquals(0, gateway.routeCount());
        assertFalse(bridge.setInboundEnabled("wecom-main", true));
        assertTrue(config.isInboundEnabled());
    }

    private static ChannelConfig config() {
        ChannelBinding binding = new ChannelBinding();
        binding.setType(ChannelBinding.TYPE_MAIN);
        binding.setInstanceId("instance");
        binding.setGroupId("bound-group");
        ChannelConfig config = new ChannelConfig();
        config.setId("wecom-main");
        config.setType(ChannelConfig.TYPE_WECOM_WS);
        config.setEnabled(true);
        config.setBinding(binding);
        return config;
    }

    private static ChannelEvent event() {
        return new ChannelEvent("wecom-main", "message-1", "user-1", "sender", "hello",
                new ChannelReplyRoute("request-1", "message-1", "user-1", "chat-1",
                        "group", System.currentTimeMillis() + 60_000));
    }
}
