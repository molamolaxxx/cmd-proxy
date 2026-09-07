package com.mola.cmd.proxy.app.acp.channel.archive;

import com.alibaba.fastjson.JSONObject;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelAttachment;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelQuotedMessage;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class ChannelMessageArchiveTest {
    @Test
    public void persistsSearchableMessageQuoteAttachmentAndRedactsCapabilities() throws Exception {
        Path root = Files.createTempDirectory("channel-message-archive-");
        try (ChannelMessageArchive archive = new ChannelMessageArchive(root)) {
            JsonObject body = JsonParser.parseString("{"
                    + "\"msgid\":\"message-1\",\"msgtype\":\"mixed\",\"chattype\":\"group\","
                    + "\"chatid\":\"chat-1\",\"chatname\":\"研发群\","
                    + "\"from\":{\"userid\":\"user-1\",\"name\":\"张三\"},"
                    + "\"image\":{\"url\":\"https://secret.example/media\",\"aeskey\":\"secret-key\"}}")
                    .getAsJsonObject();
            ChannelMessageArchive.Receipt receipt = archive.receive(
                    "archive-1", "wecom-main", "reply-capability", body);
            assertTrue(receipt.isFirst());

            byte[] bytes = "attachment bytes".getBytes(StandardCharsets.UTF_8);
            archive.completeParsed(receipt.getId(), "检查发布结果",
                    new ChannelQuotedMessage("text", "上一条发布消息"),
                    Collections.singletonList(new ChannelAttachment(
                            ChannelAttachment.Origin.CURRENT, ChannelAttachment.Kind.FILE,
                            "发布报告.txt", "text/plain", bytes)));
            archive.mark(receipt.getId(), null, "DELIVERED", null, null);

            Map<String, String> filters = new HashMap<>();
            filters.put("senderName", "张三");
            filters.put("quote", "发布");
            filters.put("attachmentName", "报告");
            JSONObject page = archive.list("archive-1", filters, 1, 20);
            assertEquals(1L, page.getLongValue("total"));
            JSONObject summary = page.getJSONArray("items").getJSONObject(0);
            assertEquals("DELIVERED", summary.getString("deliveryStatus"));
            assertEquals("发布报告.txt", summary.getJSONArray("attachments")
                    .getJSONObject(0).getString("fileName"));

            JSONObject detail = archive.detail("archive-1", receipt.getId());
            String sanitized = detail.get("sanitizedPayload").toString();
            assertFalse(sanitized.contains("secret.example"));
            assertFalse(sanitized.contains("secret-key"));
            assertTrue(sanitized.contains("REDACTED"));

            String attachmentId = detail.getJSONArray("attachments")
                    .getJSONObject(0).getString("id");
            ChannelMessageArchive.AttachmentResource resource = archive.attachment(
                    "archive-1", receipt.getId(), attachmentId);
            assertNotNull(resource);
            byte[] restored = new byte[bytes.length];
            try (InputStream input = resource.open()) {
                assertEquals(bytes.length, input.read(restored));
            }
            assertArrayEquals(bytes, restored);
            assertNull(archive.attachment("other-archive", receipt.getId(), attachmentId));
        }
    }

    @Test
    public void deduplicatesByArchiveAndProviderMessageIdAcrossReopen() throws Exception {
        Path root = Files.createTempDirectory("channel-message-dedup-");
        JsonObject body = JsonParser.parseString("{\"msgid\":\"same-id\","
                + "\"msgtype\":\"text\",\"text\":{\"content\":\"hello\"}}")
                .getAsJsonObject();
        String firstId;
        try (ChannelMessageArchive archive = new ChannelMessageArchive(root)) {
            ChannelMessageArchive.Receipt first = archive.receive(
                    "archive-1", "channel-1", "req-1", body);
            assertTrue(first.isFirst());
            firstId = first.getId();
        }
        try (ChannelMessageArchive archive = new ChannelMessageArchive(root)) {
            ChannelMessageArchive.Receipt duplicate = archive.receive(
                    "archive-1", "channel-renamed", "req-2", body);
            assertFalse(duplicate.isFirst());
            assertEquals(firstId, duplicate.getId());
            JSONObject page = archive.list("archive-1", Collections.<String, String>emptyMap(), 1, 20);
            assertEquals(1L, page.getLongValue("total"));
            assertEquals(1, page.getJSONArray("items").getJSONObject(0)
                    .getIntValue("duplicateCount"));
        }
    }
}
