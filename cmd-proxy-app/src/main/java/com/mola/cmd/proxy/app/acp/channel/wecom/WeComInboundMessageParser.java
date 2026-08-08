package com.mola.cmd.proxy.app.acp.channel.wecom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelAttachment;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelQuotedMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parses the current WeCom message and its direct quote without recursive lookup. */
final class WeComInboundMessageParser {
    interface AttachmentDownloader {
        ChannelAttachment download(JsonObject media, ChannelAttachment.Origin origin,
                                   ChannelAttachment.Kind kind, int index) throws IOException;
    }

    static final class ParsedMessage {
        private final String messageType;
        private final String text;
        private final ChannelQuotedMessage quote;
        private final List<ChannelAttachment> attachments;

        private ParsedMessage(String messageType, String text, ChannelQuotedMessage quote,
                              List<ChannelAttachment> attachments) {
            this.messageType = messageType;
            this.text = text == null ? "" : text.trim();
            this.quote = quote;
            this.attachments = Collections.unmodifiableList(attachments);
        }

        String getMessageType() { return messageType; }
        String getText() { return text; }
        ChannelQuotedMessage getQuote() { return quote; }
        List<ChannelAttachment> getAttachments() { return attachments; }
    }

    ParsedMessage parse(JsonObject body, AttachmentDownloader downloader) throws IOException {
        String type = WeComProtocol.string(body, "msgtype");
        if (!supported(type)) return null;
        List<ChannelAttachment> attachments = new ArrayList<>();
        String text = content(body, type, ChannelAttachment.Origin.CURRENT,
                attachments, downloader);

        ChannelQuotedMessage quote = null;
        JsonObject quoteBody = object(body, "quote");
        String quoteType = WeComProtocol.string(quoteBody, "msgtype");
        if (supportedQuote(quoteType)) {
            String quoteText = content(quoteBody, quoteType, ChannelAttachment.Origin.QUOTE,
                    attachments, downloader);
            quote = new ChannelQuotedMessage(quoteType, quoteText);
        }
        return new ParsedMessage(type, text, quote, attachments);
    }

    private String content(JsonObject parent, String type, ChannelAttachment.Origin origin,
                           List<ChannelAttachment> attachments,
                           AttachmentDownloader downloader) throws IOException {
        if ("text".equals(type)) return WeComProtocol.string(object(parent, "text"), "content");
        if ("voice".equals(type)) return WeComProtocol.string(object(parent, "voice"), "content");
        if ("image".equals(type)) {
            attachments.add(downloader.download(object(parent, "image"), origin,
                    ChannelAttachment.Kind.IMAGE, attachments.size() + 1));
            return "";
        }
        if ("file".equals(type)) {
            attachments.add(downloader.download(object(parent, "file"), origin,
                    ChannelAttachment.Kind.FILE, attachments.size() + 1));
            return "";
        }
        if ("mixed".equals(type)) {
            StringBuilder text = new StringBuilder();
            JsonObject mixed = object(parent, "mixed");
            JsonArray items = array(mixed, "msg_item");
            for (JsonElement element : items) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String itemType = WeComProtocol.string(item, "msgtype");
                if ("text".equals(itemType)) {
                    String value = WeComProtocol.string(object(item, "text"), "content");
                    if (value != null && !value.trim().isEmpty()) {
                        if (text.length() > 0) text.append('\n');
                        text.append(value.trim());
                    }
                } else if ("image".equals(itemType)) {
                    attachments.add(downloader.download(object(item, "image"), origin,
                            ChannelAttachment.Kind.IMAGE, attachments.size() + 1));
                }
            }
            return text.toString();
        }
        return "";
    }

    private static boolean supported(String type) {
        return "text".equals(type) || "voice".equals(type) || "image".equals(type)
                || "file".equals(type) || "mixed".equals(type);
    }

    private static boolean supportedQuote(String type) { return supported(type); }

    private static JsonObject object(JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonObject()
                ? parent.getAsJsonObject(name) : new JsonObject();
    }

    private static JsonArray array(JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonArray()
                ? parent.getAsJsonArray(name) : new JsonArray();
    }
}
