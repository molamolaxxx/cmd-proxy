package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mola.cmd.proxy.client.provider.CmdReceiver;
import com.mola.cmd.proxy.client.resp.CmdResponseContent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AcpResponseListener 的默认实现，将 agent 输出通过 sendContent 回调。
 */
public class DefaultAcpResponseListener implements AcpResponseListener {

    private static final int MAX_JSON_LENGTH = 1000;
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String groupId;

    public DefaultAcpResponseListener(String groupId) {
        this.groupId = groupId;
    }

    @Override
    public void onMessage(String text) {
        sendContent(text, false);
    }

    @Override
    public void onToolCall(String toolCallId, String title, String status, JsonObject update) {
        if ("completed".equals(status)) {
            StringBuilder sb = new StringBuilder();
            String inputBlock = "";
            if (update.has("rawInput")) {
                String inputJson = PRETTY_GSON.toJson(update.get("rawInput"));
                if (inputJson.length() > MAX_JSON_LENGTH) {
                    inputJson = inputJson.substring(0, MAX_JSON_LENGTH) + "\n...";
                }
                inputBlock = "<details class=\"tool-detail\" open><summary>📥 输入参数</summary>\n\n```json\n"
                        + inputJson + "\n```\n\n</details>";
            }

            String outputBlock = "";
            if (update.has("rawOutput")) {
                String outputJson = PRETTY_GSON.toJson(update.get("rawOutput"));
                if (outputJson.length() > MAX_JSON_LENGTH) {
                    outputJson = outputJson.substring(0, MAX_JSON_LENGTH) + "\n...";
                }
                outputBlock = "<details class=\"tool-detail\" open><summary>📤 输出结果</summary>\n\n```json\n"
                        + outputJson + "\n```\n\n</details>";
            }

            sb.append("<details class=\"tool-call\">");
            sb.append("<summary>🛠️ ✅ ").append(title).append("</summary>");
            sb.append("<div class=\"tool-call-body\">\n\n");
            sb.append(inputBlock);
            sb.append(outputBlock);
            sb.append("\n\n</div></details>\n");

            sendContent(sb.toString(), false);
        }
    }

    @Override
    public void onComplete(String fullResponse) {
        sendContent("", true);
    }

    @Override
    public void onError(Exception error) {
        sendContent("====== 发生错误 ======\n" + error.getMessage(), true);
    }

    private void sendContent(String content, boolean end) {
        Map<String, String> resultMap = new HashMap<>();
        resultMap.put("groupId", groupId);
        resultMap.put("content", content);
        resultMap.put("end", end ? "Y" : "N");
        CmdReceiver.INSTANCE.callback("acp", "acp",
                new CmdResponseContent(UUID.randomUUID().toString(), resultMap));
    }
}
