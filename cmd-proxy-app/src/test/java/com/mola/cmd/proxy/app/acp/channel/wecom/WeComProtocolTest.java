package com.mola.cmd.proxy.app.acp.channel.wecom;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.*;

public class WeComProtocolTest {

    @Test
    public void inboundConversationSelectsGroupChatIdOrDirectUserId() {
        assertEquals("group-chat-1", WeComChannelAdapter.defaultChatTarget(
                "group", "group-chat-1", "user-1"));
        assertEquals("user-1", WeComChannelAdapter.defaultChatTarget(
                "single", "", "user-1"));
    }

    @Test
    public void subscribeUsesOfficialBotIdAndSecretFields() {
        JsonObject frame = JsonParser.parseString(
                WeComProtocol.subscribe("req-1", "bot", "secret-value"))
                .getAsJsonObject();

        assertEquals("aibot_subscribe", frame.get("cmd").getAsString());
        assertEquals("req-1", frame.getAsJsonObject("headers").get("req_id").getAsString());
        assertEquals("bot", frame.getAsJsonObject("body").get("bot_id").getAsString());
        assertEquals("secret-value", frame.getAsJsonObject("body").get("secret").getAsString());
    }

    @Test
    public void finalReplyIsFinishedStreamAndProactiveMessageIsMarkdown() {
        JsonObject reply = JsonParser.parseString(
                WeComProtocol.respondMarkdown("incoming-req", "stream-1", "done"))
                .getAsJsonObject();
        JsonObject proactive = JsonParser.parseString(
                WeComProtocol.sendMarkdown("send-1", "chat-1", "notice"))
                .getAsJsonObject();

        assertEquals("aibot_respond_msg", reply.get("cmd").getAsString());
        assertTrue(reply.getAsJsonObject("body").getAsJsonObject("stream")
                .get("finish").getAsBoolean());
        assertEquals("aibot_send_msg", proactive.get("cmd").getAsString());
        assertEquals("chat-1", proactive.getAsJsonObject("body").get("chatid").getAsString());
        assertEquals("markdown", proactive.getAsJsonObject("body").get("msgtype").getAsString());
    }

    @Test
    public void callbackParserKeepsRequestIdAndTextBody() {
        WeComFrame frame = WeComProtocol.parse("{\"cmd\":\"aibot_msg_callback\","
                + "\"headers\":{\"req_id\":\"callback-1\"},"
                + "\"body\":{\"msgid\":\"msg-1\",\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"hello\"}}}");

        assertEquals("aibot_msg_callback", frame.getCmd());
        assertEquals("callback-1", frame.getRequestId());
        assertEquals("hello", frame.getBody().getAsJsonObject("text")
                .get("content").getAsString());
    }
}
