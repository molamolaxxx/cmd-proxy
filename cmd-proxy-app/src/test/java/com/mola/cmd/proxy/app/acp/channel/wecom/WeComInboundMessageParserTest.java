package com.mola.cmd.proxy.app.acp.channel.wecom;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelAttachment;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class WeComInboundMessageParserTest {

    @Test
    public void voiceTranscriptIsCurrentTextAndTriggersNoDownload() throws Exception {
        JsonObject body = JsonParser.parseString("{\"msgtype\":\"voice\","
                + "\"voice\":{\"content\":\"检查今天的构建\"}}").getAsJsonObject();

        WeComInboundMessageParser.ParsedMessage parsed = new WeComInboundMessageParser()
                .parse(body, (media, origin, kind, index) -> {
                    throw new AssertionError("voice must not download media");
                });

        assertEquals("voice", parsed.getMessageType());
        assertEquals("检查今天的构建", parsed.getText());
        assertTrue(parsed.getAttachments().isEmpty());
    }

    @Test
    public void mixedTextAndImageKeepsDirectImageQuoteOnlyOneLevel() throws Exception {
        JsonObject body = JsonParser.parseString("{\"msgtype\":\"mixed\","
                + "\"mixed\":{\"msg_item\":["
                + "{\"msgtype\":\"text\",\"text\":{\"content\":\"分析这张图\"}},"
                + "{\"msgtype\":\"image\",\"image\":{\"url\":\"https://current\",\"aeskey\":\"k\"}}]},"
                + "\"quote\":{\"msgtype\":\"image\",\"image\":{"
                + "\"url\":\"https://quote\",\"aeskey\":\"q\"},"
                + "\"quote\":{\"msgtype\":\"text\",\"text\":{\"content\":\"ignored\"}}}}")
                .getAsJsonObject();

        WeComInboundMessageParser.ParsedMessage parsed = new WeComInboundMessageParser()
                .parse(body, (media, origin, kind, index) -> new ChannelAttachment(
                        origin, kind, origin.name().toLowerCase() + ".png", "image/png",
                        origin.name().getBytes(StandardCharsets.UTF_8)));

        assertEquals("分析这张图", parsed.getText());
        assertEquals("image", parsed.getQuote().getMessageType());
        assertEquals("", parsed.getQuote().getText());
        assertEquals(2, parsed.getAttachments().size());
        assertEquals(ChannelAttachment.Origin.CURRENT,
                parsed.getAttachments().get(0).getOrigin());
        assertEquals(ChannelAttachment.Origin.QUOTE,
                parsed.getAttachments().get(1).getOrigin());
    }

    @Test
    public void fileOnlyHasAttachmentButNoActionableText() throws Exception {
        JsonObject body = JsonParser.parseString("{\"msgtype\":\"file\","
                + "\"file\":{\"url\":\"https://file\",\"aeskey\":\"k\"}}")
                .getAsJsonObject();

        WeComInboundMessageParser.ParsedMessage parsed = new WeComInboundMessageParser()
                .parse(body, (media, origin, kind, index) -> new ChannelAttachment(
                        origin, kind, "report.pdf", "application/pdf", new byte[]{1}));

        assertEquals("", parsed.getText());
        assertEquals(1, parsed.getAttachments().size());
        assertEquals(ChannelAttachment.Kind.FILE, parsed.getAttachments().get(0).getKind());
    }
}
