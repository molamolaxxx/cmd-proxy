package com.mola.cmd.proxy.app.acp.channel.archive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Removes channel credentials and short-lived media capabilities before persistence. */
final class ChannelPayloadSanitizer {
    private static final int MAX_DEPTH = 20;
    private static final int MAX_BYTES = 128 * 1024;

    String sanitize(JsonObject payload) {
        JsonElement sanitized = copy(payload == null ? new JsonObject() : payload, 0);
        String json = sanitized.toString();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) return json;
        JsonObject truncated = new JsonObject();
        truncated.addProperty("payloadTruncated", true);
        truncated.addProperty("originalBytes", bytes.length);
        return truncated.toString();
    }

    private JsonElement copy(JsonElement value, int depth) {
        if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
        if (depth >= MAX_DEPTH) return new JsonPrimitive("[TRUNCATED_DEPTH]");
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement item : value.getAsJsonArray()) result.add(copy(item, depth + 1));
            return result;
        }
        if (!value.isJsonObject()) return value.deepCopy();
        JsonObject result = new JsonObject();
        for (java.util.Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
            if (normalized.contains("aeskey") || normalized.contains("secret")
                    || normalized.contains("token") || normalized.contains("authorization")
                    || normalized.contains("cookie")) {
                result.addProperty(key, "[REDACTED]");
            } else if ("url".equals(normalized)) {
                result.addProperty(key, "[REDACTED_MEDIA_URL]");
            } else {
                result.add(key, copy(entry.getValue(), depth + 1));
            }
        }
        return result;
    }
}
