package com.mola.cmd.proxy.app.acp.talkto;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseListener;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToMessage;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * TalkTo 消息投递器，负责：
 * <ol>
 *   <li>从 LLM 输出中检测 talk_to 指令</li>
 *   <li>投递消息到目标 robot（READY 时直接投递，非 READY 时进入 inbox）</li>
 *   <li>管理每个 robot 的 inbox 队列</li>
 * </ol>
 */
public class TalkToDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(TalkToDispatcher.class);

    private static final String TALK_TO_TRIGGER = "\"action\"";
    private static final String TALK_TO_ACTION = "talk_to";

    /** 防循环：最大消息深度 */
    private static final int MAX_DEPTH = 5;

    /** inbox 容量上限 */
    private static final int INBOX_CAPACITY = 5;

    /** 短时间重复检测窗口（毫秒） */
    private static final long DEDUP_WINDOW_MS = 60_000;

    private final Map<String, AcpRobotParam> robotRegistry;
    private final AcpClientRegistry clientRegistry;
    private final Map<String, String> robotToGroupId;

    /** 每个 robot 的 inbox 队列，key 为 robotName */
    private final ConcurrentHashMap<String, LinkedBlockingQueue<TalkToMessage>> inboxes =
            new ConcurrentHashMap<>();

    /** 短时间重复检测，key 为 messageKey，value 为时间戳 */
    private final ConcurrentHashMap<String, Long> recentMessages = new ConcurrentHashMap<>();

    public TalkToDispatcher(Map<String, AcpRobotParam> robotRegistry,
                            AcpClientRegistry clientRegistry,
                            Map<String, String> robotToGroupId) {
        this.robotRegistry = robotRegistry;
        this.clientRegistry = clientRegistry;
        this.robotToGroupId = robotToGroupId;
    }

    // ==================== 指令检测 ====================

    /**
     * 从 LLM 输出中检测 talk_to 指令。
     *
     * @param fullResponse 主 Agent 当前累积的完整输出
     * @return 解析出的请求，未检测到时返回 null
     */
    public TalkToRequest detectTalkTo(String fullResponse) {
        // 查找 talk_to 关键词位置
        int actionIdx = fullResponse.indexOf("\"talk_to\"");
        if (actionIdx < 0) return null;

        // 向前找到包含该关键词的 JSON 对象起始 {
        int braceStart = fullResponse.lastIndexOf('{', actionIdx);
        if (braceStart < 0) return null;

        // 用花括号平衡提取完整 JSON
        String json = extractBalancedJson(fullResponse, braceStart);
        if (json == null) return null;

        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            String action = obj.has("action") ? obj.get("action").getAsString() : "";
            if (!"talk_to".equals(action)) return null;

            String target = obj.has("target") ? obj.get("target").getAsString() : null;
            String content = obj.has("content") ? obj.get("content").getAsString() : null;
            int depth = obj.has("_depth") ? obj.get("_depth").getAsInt() : 0;

            if (target == null || target.isEmpty() || content == null || content.isEmpty()) {
                logger.warn("talk_to 指令缺少 target 或 content");
                return null;
            }

            return new TalkToRequest(target, content, depth);
        } catch (Exception e) {
            logger.warn("talk_to JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从指定位置开始，提取花括号平衡的完整 JSON 字符串。
     */
    private static String extractBalancedJson(String text, int braceStart) {
        int braces = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = braceStart; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;

            if (c == '{') braces++;
            else if (c == '}') {
                braces--;
                if (braces == 0) {
                    return text.substring(braceStart, i + 1);
                }
            }
        }
        return null;
    }

    // ==================== 消息投递 ====================

    /**
     * 执行消息投递。
     * 目标 READY 时直接投递，非 READY 时放入 inbox。
     *
     * @param request    解析出的 talkTo 请求
     * @param senderName 发送方 robot 名称
     * @return 执行结果文本，作为 follow-up prompt 回注发送方
     */
    public String deliver(TalkToRequest request, String senderName) {
        String target = request.getTarget();
        String content = request.getContent();
        int depth = request.getDepth();

        // 1. 防循环：深度检查
        if (depth >= MAX_DEPTH) {
            logger.warn("talkTo 深度超限: sender={}, target={}, depth={}", senderName, target, depth);
            return "[talkTo 结果]\n发送失败：消息传递深度超过上限（" + MAX_DEPTH + "），可能存在循环。已终止发送。";
      }

        // 2. 防循环：短时间重复检测
        String dedupKey = senderName + "→" + target + ":" + content.hashCode();
        Long lastSent = recentMessages.get(dedupKey);
        long now = System.currentTimeMillis();
        if (lastSent != null && (now - lastSent) < DEDUP_WINDOW_MS) {
            logger.warn("talkTo 短时间重复: key={}", dedupKey);
            return "[talkTo 结果]\n发送失败：短时间内向 " + target + " 发送了相同内容，已阻止重复发送。";
        }

        // 3. 校验目标 robot 存在
        if (!robotRegistry.containsKey(target)) {
            return "[talkTo 结果]\n发送失败：robot '" + target + "' 不存在。请检查名称是否正确。";
        }

        // 4. 查找目标 groupId
        String targetGroupId = robotToGroupId.get(target);
        if (targetGroupId == null) {
            return "[talkTo 结果]\n发送失败：robot '" + target + "' 未启动（无对应的 client）。";
        }

        // 5. 获取目标 client
        AcpClient targetClient = clientRegistry.getClient(targetGroupId);
        if (targetClient == null) {
            return "[talkTo 结果]\n发送失败：robot '" + target + "' 的 client 不存在。";
        }

        // 6. 记录发送记录（用于去重）
        recentMessages.put(dedupKey, now);
        cleanExpiredDedup();

        // 7. 构造消息
        TalkToMessage message = new TalkToMessage(senderName, content, depth + 1);

        // 8. 检查目标状态并投递
        if (targetClient.getState() == AbstractAcpClient.State.READY) {
            // 直接投递：先推送来信卡片到目标前端
            pushIncomingMessageCard(targetClient, senderName, content);
            targetClient.send(message.buildPrompt(), null);
            logger.info("talkTo 直接投递: {} → {}", senderName, target);
            return "[talkTo 结果]\n已成功将消息发送给 " + target + "。对方会处理你的请求，你可以继续当前工作。";
        } else {
            // 放入 inbox
            LinkedBlockingQueue<TalkToMessage> inbox = inboxes.computeIfAbsent(
                    target, k -> new LinkedBlockingQueue<>(INBOX_CAPACITY));

            if (inbox.offer(message)) {
                int queueSize = inbox.size();
                logger.info("talkTo 入队: {} → {}, 队列位置={}/{}", senderName, target, queueSize, INBOX_CAPACITY);
                return "[talkTo 结果]\n" + target + " 当前正忙，消息已放入对方的待处理队列（第 "
                        + queueSize + "/" + INBOX_CAPACITY + " 条）。对方空闲后会自动收到。";
            } else {
                logger.warn("talkTo inbox 已满: {} → {}", senderName, target);
                return "[talkTo 结果]\n发送失败：" + target + " 的消息队列已满（"
                        + INBOX_CAPACITY + "/" + INBOX_CAPACITY + "），无法接收新消息。"
                        + "你可以稍后再试，或使用 dispatch_subagent 创建独立子进程执行。";
            }
        }
    }

    // ==================== Inbox 管理 ====================

    /**
     * 从目标 robot 的 inbox 中取出下一条待处理消息。
     * 在 AcpClient turn 结束后调用。
     *
     * @param robotName 目标 robot 名称
     * @return 待投递的消息，inbox 为空时返回 null
     */
    public TalkToMessage pollInbox(String robotName) {
        LinkedBlockingQueue<TalkToMessage> inbox = inboxes.get(robotName);
        if (inbox == null) return null;
        return inbox.poll();
    }

    /**
     * 清理过期的去重记录。
     */
    private void cleanExpiredDedup() {
        long now = System.currentTimeMillis();
        recentMessages.entrySet().removeIf(entry -> (now - entry.getValue()) > DEDUP_WINDOW_MS);
    }

    /**
     * 向目标 client 的前端推送"来信卡片"，让用户知道即将收到的消息来自哪个 robot。
     */
    private void pushIncomingMessageCard(AcpClient targetClient, String senderName, String content) {
        AcpResponseListener listener = targetClient.getGlobalListener();
        if (listener == null) return;
        listener.onTalkToEvent("TALK_TO_RECEIVE", senderName, content);
    }

    /**
     * 从 inbox 投递消息时推送来信卡片。
     * 供 AcpClient.checkAndDeliverInbox() 调用。
     */
    public void pushIncomingMessageCard(AcpClient targetClient, TalkToMessage message) {
        pushIncomingMessageCard(targetClient, message.getSender(), message.getContent());
    }
}
