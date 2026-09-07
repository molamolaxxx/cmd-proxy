package com.mola.cmd.proxy.app.acp.channel.wecom;

import com.alibaba.fastjson.JSONObject;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.channel.ChannelTalkToBridge;
import com.mola.cmd.proxy.app.acp.channel.ChannelTalkToGateway;
import com.mola.cmd.proxy.app.acp.channel.archive.ChannelMessageArchive;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelBinding;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelConfig;
import org.junit.Test;

import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WeComChannelArchiveIntegrationTest {
    @Test
    public void recordsPolicyIgnoredMessageBeforeParsingOrDelivery() throws Exception {
        ChannelConfig config = new ChannelConfig();
        config.setId("wecom-main");
        config.setArchiveId("archive-main");
        config.setType(ChannelConfig.TYPE_WECOM_WS);
        config.setEnabled(true);
        config.setInboundEnabled(false);
        ChannelBinding binding = new ChannelBinding();
        binding.setType(ChannelBinding.TYPE_MAIN);
        binding.setInstanceId("instance");
        binding.setGroupId("group");
        config.setBinding(binding);
        Map<String, ChannelConfig> configs = new HashMap<>();
        configs.put(config.getId(), config);
        ChannelTalkToBridge bridge = new ChannelTalkToBridge(configs, ignored -> {
            throw new AssertionError("policy ignored message must not resolve a client");
        }, new ChannelTalkToGateway(Collections.emptyMap()));

        JsonObject body = JsonParser.parseString("{\"msgid\":\"ignored-1\","
                + "\"msgtype\":\"text\",\"chattype\":\"single\","
                + "\"from\":{\"userid\":\"user-1\",\"name\":\"张三\"},"
                + "\"text\":{\"content\":\"仍然需要记录\"}}")
                .getAsJsonObject();
        WeComFrame frame = WeComProtocol.parse("{\"cmd\":\"aibot_msg_callback\","
                + "\"headers\":{\"req_id\":\"req-1\"},\"body\":" + body + "}");

        try (ChannelMessageArchive archive = new ChannelMessageArchive(
                Files.createTempDirectory("wecom-policy-archive-"))) {
            ChannelMessageArchive.Receipt receipt = archive.receive(config.getArchiveId(),
                    config.getId(), frame.getRequestId(), body);
            assertTrue(receipt.isFirst());
            WeComChannelAdapter adapter = new WeComChannelAdapter(config, bridge, archive);
            adapter.processMessage(frame, receipt.getId());

            JSONObject detail = archive.detail(config.getArchiveId(), receipt.getId());
            assertEquals("IGNORED_POLICY", detail.getString("deliveryStatus"));
            assertEquals("CHANNEL_INBOUND_DISABLED", detail.getString("resultCode"));
            assertEquals("ignored-1", detail.getString("messageId"));
            assertEquals("张三", detail.getJSONObject("sender").getString("name"));
        }
    }
}
