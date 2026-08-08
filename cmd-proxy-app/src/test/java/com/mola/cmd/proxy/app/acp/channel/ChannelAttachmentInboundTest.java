package com.mola.cmd.proxy.app.acp.channel;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelAttachment;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelBinding;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelConfig;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelEvent;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelQuotedMessage;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelReplyRoute;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class ChannelAttachmentInboundTest {

    @Test
    public void attachmentOnlyIsSavedInWorkspaceWithoutAcpOrReplyRoute() throws Exception {
        Path workspace = Files.createTempDirectory("channel-file-only-");
        RecordingClient client = new RecordingClient(workspace);
        Fixture fixture = fixture(client);
        ChannelAttachment attachment = attachment(ChannelAttachment.Origin.CURRENT,
                "../unsafe.pdf", "payload");

        TalkToDispatcher.InboundDeliveryResult result = fixture.bridge.onEvent(event(
                "file-message", "file", "", null, attachment));

        assertEquals(TalkToDispatcher.InboundDeliveryResult.Status.SAVED, result.getStatus());
        assertNull(client.prompt);
        assertEquals(AbstractAcpClient.State.READY, client.getState());
        assertEquals(0, fixture.gateway.routeCount());
        Path directory = workspace.resolve(".cmd-proxy/inbox/wecom/wecom-main/file-message");
        assertTrue(Files.isDirectory(directory));
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            Path saved = files.findFirst().orElse(null);
            assertNotNull(saved);
            assertTrue(saved.getFileName().toString().startsWith("current-"));
            assertFalse(saved.getFileName().toString().contains(".."));
            assertEquals("payload", new String(Files.readAllBytes(saved), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void textQuotingImageSendsQuoteAndStagedPathToAcp() throws Exception {
        Path workspace = Files.createTempDirectory("channel-quote-image-");
        RecordingClient client = new RecordingClient(workspace);
        Fixture fixture = fixture(client);

        TalkToDispatcher.InboundDeliveryResult result = fixture.bridge.onEvent(event(
                "quote-message", "text", "分析引用图片",
                new ChannelQuotedMessage("image", null),
                attachment(ChannelAttachment.Origin.QUOTE, "screen.png", "png")));

        assertEquals(TalkToDispatcher.InboundDeliveryResult.Status.DIRECT, result.getStatus());
        assertNotNull(client.prompt);
        assertTrue(client.prompt.contains("分析引用图片"));
        assertTrue(client.prompt.contains("引用消息（仅向上溯源一层"));
        assertEquals(1, client.localFiles.size());
        assertTrue(client.localFiles.get(0).contains("quote-screen.png"));
        assertTrue(Files.isRegularFile(java.nio.file.Paths.get(client.localFiles.get(0))));
        assertEquals(1, fixture.gateway.routeCount());
    }

    private static ChannelAttachment attachment(ChannelAttachment.Origin origin,
                                                String name, String value) {
        return new ChannelAttachment(origin, ChannelAttachment.Kind.FILE, name,
                "application/octet-stream", value.getBytes(StandardCharsets.UTF_8));
    }

    private static ChannelEvent event(String id, String type, String text,
                                      ChannelQuotedMessage quote,
                                      ChannelAttachment attachment) {
        return new ChannelEvent("wecom-main", id, "user-1", "sender", type, text,
                quote, Collections.singletonList(attachment),
                new ChannelReplyRoute("request-1", id, "user-1", "chat-1", "group",
                        System.currentTimeMillis() + 60_000));
    }

    private static Fixture fixture(RecordingClient client) {
        ChannelBinding binding = new ChannelBinding();
        binding.setType(ChannelBinding.TYPE_MAIN);
        binding.setInstanceId("instance");
        binding.setGroupId("bound-group");
        ChannelConfig config = new ChannelConfig();
        config.setId("wecom-main");
        config.setType(ChannelConfig.TYPE_WECOM_WS);
        config.setEnabled(true);
        config.setBinding(binding);
        Map<String, ChannelConfig> configs = new HashMap<>();
        configs.put(config.getId(), config);
        ChannelTalkToGateway gateway = new ChannelTalkToGateway(Collections.emptyMap(), configs);
        TalkToDispatcher dispatcher = new TalkToDispatcher(Collections.emptyMap(),
                AcpClientRegistry.getInstance(), Collections.emptyMap());
        ChannelTalkToBridge bridge = new ChannelTalkToBridge(configs,
                groupId -> client, dispatcher, gateway);
        return new Fixture(bridge, gateway);
    }

    private static final class Fixture {
        private final ChannelTalkToBridge bridge;
        private final ChannelTalkToGateway gateway;
        private Fixture(ChannelTalkToBridge bridge, ChannelTalkToGateway gateway) {
            this.bridge = bridge;
            this.gateway = gateway;
        }
    }

    private static final class RecordingClient extends AcpClient {
        private String prompt;
        private List<String> localFiles = Collections.emptyList();

        private RecordingClient(Path workspace) {
            super(workspace.toString(), "bound-group", robot());
            state.set(State.READY);
        }

        @Override public void sendLocalFiles(String input, List<String> files) {
            this.prompt = input;
            this.localFiles = files;
            state.set(State.BUSY);
        }

        private static AcpRobotParam robot() {
            AcpRobotParam robot = new AcpRobotParam();
            robot.setName("robot");
            return robot;
        }
    }

}
