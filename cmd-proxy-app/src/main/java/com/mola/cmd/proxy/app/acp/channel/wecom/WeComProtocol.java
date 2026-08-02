package com.mola.cmd.proxy.app.acp.channel.wecom;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.UUID;

/** Pure frame codec kept independent from OkHttp for focused protocol tests. */
public final class WeComProtocol {
    public static final String SUBSCRIBE = "aibot_subscribe";
    public static final String MESSAGE_CALLBACK = "aibot_msg_callback";
    public static final String EVENT_CALLBACK = "aibot_event_callback";
    public static final String RESPOND_MESSAGE = "aibot_respond_msg";
    public static final String SEND_MESSAGE = "aibot_send_msg";
    public static final String PING = "ping";
    private static final Gson GSON = new Gson();

    private WeComProtocol() {}

    public static String requestId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    public static String subscribe(String requestId, String botId, String secret) {
        JsonObject body = new JsonObject();
        body.addProperty("bot_id", botId);
        body.addProperty("secret", secret);
        return frame(SUBSCRIBE, requestId, body);
    }

    public static String ping(String requestId) {
        return frame(PING, requestId, new JsonObject());
    }

    public static String respondMarkdown(String requestId, String streamId, String markdown) {
        JsonObject stream = new JsonObject();
        stream.addProperty("id", streamId);
        stream.addProperty("finish", true);
        stream.addProperty("content", markdown);
        JsonObject body = new JsonObject();
        body.addProperty("msgtype", "stream");
        body.add("stream", stream);
        return frame(RESPOND_MESSAGE, requestId, body);
    }

    public static String sendMarkdown(String requestId, String chatId, String markdown) {
        JsonObject markdownBody = new JsonObject();
        markdownBody.addProperty("content", markdown);
        JsonObject body = new JsonObject();
        body.addProperty("chatid", chatId);
        body.addProperty("msgtype", "markdown");
        body.add("markdown", markdownBody);
        return frame(SEND_MESSAGE, requestId, body);
    }

    public static WeComFrame parse(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        String cmd = string(object, "cmd");
        JsonObject headers = object.has("headers") && object.get("headers").isJsonObject()
                ? object.getAsJsonObject("headers") : new JsonObject();
        String requestId = string(headers, "req_id");
        Integer errorCode = object.has("errcode") && !object.get("errcode").isJsonNull()
                ? object.get("errcode").getAsInt() : null;
        String errorMessage = string(object, "errmsg");
        JsonObject body = object.has("body") && object.get("body").isJsonObject()
                ? object.getAsJsonObject("body") : new JsonObject();
        return new WeComFrame(cmd, requestId, errorCode, errorMessage, body);
    }

    public static String string(JsonObject object, String name) {
        if (object == null || !object.has(name)) return null;
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static String frame(String cmd, String requestId, JsonObject body) {
        JsonObject headers = new JsonObject();
        headers.addProperty("req_id", requestId);
        JsonObject frame = new JsonObject();
        frame.addProperty("cmd", cmd);
        frame.add("headers", headers);
        frame.add("body", body);
        return GSON.toJson(frame);
    }
}
