package com.mola.cmd.proxy.app.acp.acpclient.listener;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * ACP 回调的唯一展示渲染器。
 *
 * <p>普通 ACP 与 Fast Team 必须复用本类，保证文本、工具、子 Agent、
 * schedule 与 compaction 卡片的 HTML、顺序和完成边界一致。身份和传输
 * envelope 由各自 listener 的 {@link Output} 实现负责。</p>
 */
public final class AcpResponseContentRenderer {

    private static final int MAX_JSON_LENGTH = 1000;
    private static final Gson PRETTY_GSON =
            new GsonBuilder().setPrettyPrinting().create();

    @FunctionalInterface
    public interface Output {
        void emit(String content, boolean end);
    }

    private final Output output;
    private char lastChar = '\0';

    public AcpResponseContentRenderer(Output output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    public void onMessage(String text) {
        sendContent(text == null ? "" : text, false);
    }

    public void onToolCall(String title, String status, JsonObject update) {
        String safeTitle = title;
        if (safeTitle == null || safeTitle.isEmpty()) {
            safeTitle = "tool_call";
        }
        if (!"completed".equals(status)) {
            return;
        }

        JsonObject safeUpdate = update == null ? new JsonObject() : update;
        String inputBlock = "";
        if (safeUpdate.has("rawInput")) {
            String inputJson = truncate(PRETTY_GSON.toJson(
                    safeUpdate.get("rawInput")));
            inputBlock = "<details class=\"tool-detail\" open><summary>📥 输入参数</summary>\n\n```json\n"
                    + escapeHtml(inputJson) + "\n```\n\n</details>";
        }

        String outputBlock = "";
        if (safeUpdate.has("rawOutput")) {
            String outputJson = truncate(PRETTY_GSON.toJson(
                    safeUpdate.get("rawOutput")));
            outputBlock = "<details class=\"tool-detail\" open><summary>📤 输出结果</summary>\n\n```json\n"
                    + escapeHtml(outputJson) + "\n```\n\n</details>";
        }

        String content = "<details class=\"tool-call\">"
                + "<summary>🛠️ ✅ " + escapeSummaryText(safeTitle) + "</summary>"
                + "<div class=\"tool-call-body\">\n\n"
                + inputBlock + outputBlock
                + "\n\n</div></details>\n";
        sendCardContent(content);
    }

    public void onSubAgentEvent(String eventType, String agentName,
                                String detail) {
        String safeDetail = sanitizeCodeFences(detail);
        String safeName = escapeSummaryText(agentName);
        String content;
        switch (eventType == null ? "" : eventType) {
            case "DISPATCH_START":
                content = "<details class=\"tool-call\" open>"
                        + "<summary>📋 子 Agent 派发</summary>"
                        + "<div class=\"tool-call-body\">\n\n```\n"
                        + safeDetail + "\n```\n\n</div></details>\n";
                break;
            case "AGENT_START":
                content = "<details class=\"tool-call\">"
                        + "<summary>🚀 [" + safeName + "] 执行中...</summary>"
                        + "<div class=\"tool-call-body\">\n\n"
                        + "<details class=\"tool-detail\" open><summary>📥 任务</summary>\n\n```\n"
                        + safeDetail + "\n```\n\n</details>"
                        + "\n\n</div></details>\n";
                break;
            case "AGENT_PROGRESS":
                content = "<details class=\"tool-call\">"
                        + "<summary>⏳ [" + safeName + "] 执行中...</summary>"
                        + "<div class=\"tool-call-body\">\n\n"
                        + "<details class=\"tool-detail\" open><summary>📋 当前进度</summary>\n\n```\n"
                        + safeDetail + "\n```\n\n</details>"
                        + "\n\n</div></details>\n";
                break;
            case "AGENT_COMPLETE":
                content = "<details class=\"tool-call\">"
                        + "<summary>🤖 ✅ [" + safeName + "] 完成</summary>"
                        + "<div class=\"tool-call-body\">\n\n"
                        + "<details class=\"tool-detail\" open><summary>📤 执行结果</summary>\n\n```\n"
                        + safeDetail + "\n```\n\n</details>"
                        + "\n\n</div></details>\n";
                break;
            case "AGENT_ERROR":
                content = "<details class=\"tool-call\">"
                        + "<summary>🤖 ❌ [" + safeName + "] 失败</summary>"
                        + "<div class=\"tool-call-body\">\n\n"
                        + "<details class=\"tool-detail\" open><summary>📤 错误信息</summary>\n\n```\n"
                        + safeDetail + "\n```\n\n</details>"
                        + "\n\n</div></details>\n";
                break;
            case "DISPATCH_COMPLETE":
                content = "<details class=\"tool-call\">"
                        + "<summary>📊 " + escapeSummaryText(safeDetail)
                        + "</summary></details>\n";
                break;
            default:
                content = "<details class=\"tool-call\">"
                        + "<summary>ℹ️ " + escapeSummaryText(safeDetail)
                        + "</summary></details>\n";
        }
        sendCardContent(content);
    }

    public void onScheduleEvent(String eventType, String detail,
                                boolean expanded) {
        String safeDetail = sanitizeCodeFences(detail);
        String openAttr = expanded ? " open" : "";
        String content;
        switch (eventType == null ? "" : eventType) {
            case "SCHEDULE_CREATE":
                content = "<details class=\"tool-call\"" + openAttr + ">"
                        + "<summary>⏰ 定时任务创建</summary>"
                        + "<div class=\"tool-call-body\">\n\n```\n"
                        + safeDetail + "\n```\n\n</div></details>\n";
                break;
            case "SCHEDULE_MANAGE":
                content = "<details class=\"tool-call\"" + openAttr + ">"
                        + "<summary>⏰ 定时任务操作</summary>"
                        + "<div class=\"tool-call-body\">\n\n```\n"
                        + safeDetail + "\n```\n\n</div></details>\n";
                break;
            case "SCHEDULE_EXECUTE":
                content = "<details class=\"tool-call\"" + openAttr + ">"
                        + "<summary>⏰ 定时任务执行中...</summary>"
                        + "<div class=\"tool-call-body\">\n\n```\n"
                        + safeDetail + "\n```\n\n</div></details>\n";
                break;
            default:
                content = "<details class=\"tool-call\"" + openAttr + ">"
                        + "<summary>⏰ " + escapeSummaryText(safeDetail)
                        + "</summary></details>\n";
        }
        sendCardContent(content);
    }

    public void onTalkToEvent(String eventType, String robotName,
                              String messageContent) {
        sendCardContent(talkToCardContent(eventType, robotName, messageContent));
    }

    public static String talkToCardContent(String eventType, String robotName,
                                           String messageContent) {
        String safeName = escapeSummaryText(robotName);
        String safeContent = sanitizeCodeFences(messageContent);
        String summary;
        if ("TALK_TO_SEND".equals(eventType)) {
            summary = "📤 发送消息给 " + safeName;
        } else if ("TALK_TO_RECEIVE".equals(eventType)) {
            summary = "📨 收到来自 " + safeName + " 的消息";
        } else {
            summary = "💬 " + safeName;
        }
        return "<details class=\"tool-call\" open>"
                + "<summary>" + summary + "</summary>"
                + "<div class=\"tool-call-body\">\n\n```\n"
                + safeContent + "\n```\n\n</div></details>\n";
    }

    public void onCompactionEvent(String eventType, String provider) {
        if (!"COMPACTION_COMPLETED".equals(eventType)) {
            return;
        }
        sendCardContent("<details class=\"tool-call\">"
                + "<summary>🗜️ ✅ 上下文压缩完成</summary>"
                + "<div class=\"tool-call-body\">\n\n"
                + "Agent 已完成上下文压缩，下一轮对话将重新注入完整 ACP harness。"
                + "\n\nProvider：<code>" + escapeHtml(provider) + "</code>"
                + "\n\n</div></details>\n");
    }

    public void onComplete() {
        sendContent("", true);
    }

    public void onError(Exception error) {
        sendContent(errorContent(error), true);
    }

    public static String errorContent(Exception error) {
        return "====== 发生错误 ======\n"
                + (error == null ? null : error.getMessage());
    }

    private static String truncate(String value) {
        return value.length() > MAX_JSON_LENGTH
                ? value.substring(0, MAX_JSON_LENGTH) + "\n..." : value;
    }

    private static String escapeHtml(String text) {
        return text == null ? ""
                : text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String escapeSummaryText(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            switch (text.charAt(i)) {
                case '&': escaped.append("&amp;"); break;
                case '<': escaped.append("&lt;"); break;
                case '>': escaped.append("&gt;"); break;
                case '#': escaped.append("&#35;"); break;
                case '*': escaped.append("&#42;"); break;
                default: escaped.append(text.charAt(i));
            }
        }
        return escaped.toString();
    }

    private static String sanitizeCodeFences(String text) {
        return text == null ? ""
                : text.replaceAll("```[a-zA-Z]*", "").replace("```", "");
    }

    private void sendCardContent(String content) {
        String prefix = lastChar != '\0' && lastChar != '\n' ? "\n" : "";
        sendContent(prefix + content, false);
    }

    private void sendContent(String content, boolean end) {
        if (!content.isEmpty()) {
            lastChar = content.charAt(content.length() - 1);
        }
        output.emit(content, end);
    }
}
