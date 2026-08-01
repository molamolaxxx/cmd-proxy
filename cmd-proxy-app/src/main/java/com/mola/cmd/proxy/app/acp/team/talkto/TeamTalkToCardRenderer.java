package com.mola.cmd.proxy.app.acp.team.talkto;

/**
 * Team talkTo 对话内可视卡片。路由标识始终是不可变 teamMemberId；
 * displayName 只用于辅助展示。
 */
public final class TeamTalkToCardRenderer {

    private TeamTalkToCardRenderer() {
    }

    public static String render(String eventType, String peerTeamMemberId,
                                String peerDisplayName, String content,
                                String delivery, String reason) {
        String memberId = requireMemberId(peerTeamMemberId);
        String label = escapeHtml(memberId);
        if (peerDisplayName != null && !peerDisplayName.trim().isEmpty()
                && !memberId.equals(peerDisplayName.trim())) {
            label += "（" + escapeHtml(peerDisplayName.trim()) + "）";
        }
        String icon;
        String title;
        if ("TALK_TO_RECEIVE".equals(eventType)) {
            icon = "📨";
            title = "收到来自 " + label + " 的 Team 消息";
        } else if ("TALK_TO_QUEUED".equals(eventType)) {
            icon = "⏳";
            title = "消息已排队给 " + label;
        } else if ("TALK_TO_REJECTED".equals(eventType)) {
            icon = "⚠️";
            title = "发送给 " + label + " 的消息未投递";
        } else {
            icon = "📤";
            title = "发送 Team 消息给 " + label;
        }
        StringBuilder card = new StringBuilder();
        card.append("<details class=\"tool-call team-talk-to-card\" open")
                .append(" data-team-member-id=\"")
                .append(escapeAttribute(memberId)).append("\">")
                .append("<summary>").append(icon).append(" ")
                .append(title).append("</summary>")
                .append("<div class=\"tool-call-body\">\n\n")
                .append("路由 target：`").append(escapeBackticks(memberId))
                .append("`\n\n")
                .append("投递状态：`").append(escapeBackticks(
                        delivery == null ? "" : delivery)).append("`\n\n");
        if (reason != null && !reason.trim().isEmpty()) {
            card.append("原因：`").append(escapeBackticks(reason.trim()))
                    .append("`\n\n");
        }
        card.append("```\n").append(sanitizeCodeFences(content))
                .append("\n```\n\n</div></details>\n");
        return card.toString();
    }

    private static String requireMemberId(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9._-]+")
                || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException(
                    "peerTeamMemberId must be a safe immutable ID");
        }
        return value;
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("#", "\\#")
                .replace("*", "\\*");
    }

    private static String escapeAttribute(String value) {
        return escapeHtml(value).replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String escapeBackticks(String value) {
        return value == null ? "" : value.replace("`", "'");
    }

    private static String sanitizeCodeFences(String value) {
        if (value == null) return "";
        return value.replaceAll("```[a-zA-Z]*", "").replace("```", "");
    }
}
