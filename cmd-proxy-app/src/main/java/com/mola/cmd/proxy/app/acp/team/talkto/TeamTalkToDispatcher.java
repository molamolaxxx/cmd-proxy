package com.mola.cmd.proxy.app.acp.team.talkto;

import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.acpclient.listener.AcpResponseContentRenderer;
import com.mola.cmd.proxy.app.acp.channel.ChannelTalkToMessage;
import com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher;
import com.mola.cmd.proxy.app.acp.talkto.model.ContactRef;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToMessage;
import com.mola.cmd.proxy.app.acp.talkto.model.TalkToRequest;
import com.mola.cmd.proxy.app.acp.team.TeamClientRegistry;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventSink;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventType;
import com.mola.cmd.proxy.app.acp.team.listener.TeamMemberStateObserver;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberState;
import com.mola.cmd.proxy.app.acp.team.model.TeamState;
import com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.LongSupplier;

/**
 * 单个 Team 共享的严格队内 talkTo 路由器。
 *
 * <p>它完全绕开普通 robot registry/crossTalkTo，目标只接受当前 Team
 * 的不可变 teamMemberId。每个目标 member 有独立 FIFO inbox。</p>
 */
public final class TeamTalkToDispatcher extends TalkToDispatcher
        implements AutoCloseable {

    public static final int INBOX_CAPACITY = 10;
    public static final long INBOX_TTL_MS = 30L * 60L * 1000L;
    public static final long DEDUP_WINDOW_MS = 60_000L;
    public static final int MAX_DEPTH = 5;

    private final TeamRuntime runtime;
    private final TeamClientRegistry clientRegistry;
    private final TeamEventSink eventSink;
    private final TeamMemberStateObserver stateObserver;
    private final LongSupplier clock;
    private final long inboxTtlMillis;
    private final long dedupWindowMillis;
    private final ConcurrentHashMap<String, LinkedBlockingQueue<QueuedMessage>> inboxes =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recentMessages =
            new ConcurrentHashMap<>();
    private volatile boolean closed;

    public TeamTalkToDispatcher(TeamRuntime runtime,
                                TeamClientRegistry clientRegistry,
                                TeamEventSink eventSink) {
        this(runtime, clientRegistry, eventSink, TeamMemberStateObserver.NOOP);
    }

    public TeamTalkToDispatcher(TeamRuntime runtime,
                                TeamClientRegistry clientRegistry,
                                TeamEventSink eventSink,
                                TeamMemberStateObserver stateObserver) {
        this(runtime, clientRegistry, eventSink, System::currentTimeMillis,
                INBOX_TTL_MS, DEDUP_WINDOW_MS, stateObserver);
    }

    TeamTalkToDispatcher(TeamRuntime runtime,
                         TeamClientRegistry clientRegistry,
                         TeamEventSink eventSink,
                         LongSupplier clock,
                         long inboxTtlMillis,
                         long dedupWindowMillis) {
        this(runtime, clientRegistry, eventSink, clock, inboxTtlMillis,
                dedupWindowMillis, TeamMemberStateObserver.NOOP);
    }

    TeamTalkToDispatcher(TeamRuntime runtime,
                         TeamClientRegistry clientRegistry,
                         TeamEventSink eventSink,
                         LongSupplier clock,
                         long inboxTtlMillis,
                         long dedupWindowMillis,
                         TeamMemberStateObserver stateObserver) {
        super();
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.clientRegistry = Objects.requireNonNull(clientRegistry, "clientRegistry");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.stateObserver = Objects.requireNonNull(stateObserver, "stateObserver");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (inboxTtlMillis < 1L || dedupWindowMillis < 1L) {
            throw new IllegalArgumentException("talkTo TTL windows must be positive");
        }
        this.inboxTtlMillis = inboxTtlMillis;
        this.dedupWindowMillis = dedupWindowMillis;
    }

    @Override
    public boolean managesTalkToEvents() {
        return true;
    }

    @Override
    public String deliver(TalkToRequest request, String senderName,
                          String ignoredSenderChatterId,
                          List<ContactRef> ignoredContacts) {
        if (request == null) {
            return reject(null, senderName, null, "", 0,
                    "INVALID_REQUEST", "talk_to request is required");
        }
        String content = request.getContent() == null ? "" : request.getContent().trim();
        TeamDefinition team = runtime.getDefinition();
        TeamMemberDefinition sender = findMember(team, senderName);
        TeamMemberDefinition target = findMember(team, request.getTarget());
        if (closed || !runtime.isAcceptingRequests()) {
            return reject(sender, senderName, target, content, request.getDepth(),
                    "TEAM_CLOSED", "Team is not accepting talk_to messages");
        }
        if (team.getState() != TeamState.READY) {
            return reject(sender, senderName, target, content, request.getDepth(),
                    "TEAM_NOT_READY", "Team is not READY");
        }
        if (sender == null) {
            return reject(null, senderName, target, content, request.getDepth(),
                    "SENDER_NOT_IN_TEAM", "sender is not a member of this Team");
        }
        if (request.getDepth() < 0) {
            return reject(sender, senderName, target, content, request.getDepth(),
                    "INVALID_DEPTH", "消息传递深度不能为负数");
        }
        if (request.getDepth() >= MAX_DEPTH) {
            return reject(sender, senderName, target, content, request.getDepth(),
                    "DEPTH_EXCEEDED", "消息传递深度超过上限（" + MAX_DEPTH + "）");
        }
        if (target == null) {
            return reject(sender, senderName, null, content, request.getDepth(),
                    "TARGET_NOT_IN_TEAM",
                    "target 必须是当前 Team 通讯录中的 teamMemberId");
        }
        if (sender.getTeamMemberId().equals(target.getTeamMemberId())) {
            return reject(sender, senderName, target, content, request.getDepth(),
                    "SELF_TARGET", "不能向自己发送 talk_to 消息");
        }
        if (content.isEmpty()) {
            return reject(sender, senderName, target, content, request.getDepth(),
                    "EMPTY_CONTENT", "content 不能为空");
        }

        long now = clock.getAsLong();
        cleanExpiredDedup(now);
        String dedupKey = team.getTeamId() + "/" + sender.getTeamMemberId()
                + "→" + target.getTeamMemberId() + ":" + contentDigest(content);
        Long previous = recentMessages.putIfAbsent(dedupKey, now);
        if (previous != null && now - previous < dedupWindowMillis) {
            return reject(sender, senderName, target, content, request.getDepth(),
                    "DUPLICATE", "短时间内相同消息已被 Team talk_to 接收");
        }
        if (previous != null) {
            recentMessages.replace(dedupKey, previous, now);
        }

        String messageId = UUID.randomUUID().toString();
        int nextDepth = request.getDepth() + 1;
        TeamTalkToMessage message = new TeamTalkToMessage(
                messageId, sender.getTeamMemberId(), content, nextDepth);
        AcpClient targetClient = clientRegistry.get(
                team.getTeamId(), target.getTeamMemberId()).orElse(null);
        if (targetClient != null) {
            synchronized (targetClient) {
                if (target.getState() == TeamMemberState.READY
                        && targetClient.getState() == AbstractAcpClient.State.READY) {
                    stateObserver.onState(team.getTeamId(), target.getTeamMemberId(),
                            TeamMemberState.BUSY, null);
                    publish(sender, TeamEventType.TALK_TO_SEND,
                            eventData(messageId, sender, target, content, nextDepth,
                                    "DELIVERED", null, null, null));
                    publishCard(sender, target, TeamEventType.TALK_TO_SEND,
                            messageId, content, "DELIVERED", null);
                    publish(target, TeamEventType.TALK_TO_RECEIVE,
                            eventData(messageId, sender, target, content, nextDepth,
                                    "DELIVERED", null, null, null));
                    publishCard(target, sender, TeamEventType.TALK_TO_RECEIVE,
                            messageId, content, "DELIVERED", null);
                    targetClient.send(message.buildPrompt(), null);
                    return "[talkTo 结果]\n已成功将消息发送给同队成员 "
                            + target.getDisplayName() + "（" + target.getTeamMemberId()
                            + "）。对方会处理你的请求，你可以继续当前工作。";
                }
            }
        }

        if (targetClient != null
                && (target.getState() == TeamMemberState.BUSY
                || targetClient.getState() == AbstractAcpClient.State.BUSY)) {
            LinkedBlockingQueue<QueuedMessage> inbox = inboxes.computeIfAbsent(
                    target.getTeamMemberId(),
                    ignored -> new LinkedBlockingQueue<>(INBOX_CAPACITY));
            purgeExpired(inbox, now);
            long expiresAt = now + inboxTtlMillis;
            if (inbox.offer(new QueuedMessage(
                    messageId, message, sender, target, expiresAt))) {
                int position = inbox.size();
                publish(sender, TeamEventType.TALK_TO_QUEUED,
                        eventData(messageId, sender, target, content, nextDepth,
                                "QUEUED", position, expiresAt, null));
                publishCard(sender, target, TeamEventType.TALK_TO_QUEUED,
                        messageId, content, "QUEUED", null);
                return "[talkTo 结果]\n" + target.getDisplayName()
                        + " 当前正忙，消息已放入其 Team inbox（第 "
                        + position + "/" + INBOX_CAPACITY
                        + " 条，30 分钟内有效）。对方空闲后会自动收到。";
            }
            recentMessages.remove(dedupKey, now);
            return reject(sender, senderName, target, content, request.getDepth(),
                    "INBOX_FULL", "目标 Team inbox 已满（"
                            + INBOX_CAPACITY + "/" + INBOX_CAPACITY + "）");
        }

        recentMessages.remove(dedupKey, now);
        return reject(sender, senderName, target, content, request.getDepth(),
                "TARGET_NOT_READY", "目标 Team member 当前不可投递且不处于可排队的 BUSY 状态");
    }

    @Override
    public String deliver(TalkToRequest request, String senderName,
                          String senderChatterId, String ignoredSenderGroupId,
                          List<ContactRef> contacts) {
        if (request != null && request.getTarget() != null
                && request.getTarget().startsWith("channel:")) {
            String senderKey = "team:" + runtime.getDefinition().getTeamId()
                    + ":" + senderName;
            String result = super.deliver(
                    request, senderName, senderChatterId, senderKey, contacts);
            pushExternalTalkToCard(senderName, "TALK_TO_SEND",
                    channelLabel(request.getTarget()), request.getContent());
            return result;
        }
        return deliver(request, senderName, senderChatterId, contacts);
    }

    @Override
    public InboundDeliveryResult deliverInbound(String teamMemberId, AcpClient targetClient,
                                                 TalkToMessage message) {
        if (teamMemberId == null || targetClient == null || message == null || closed) {
            return InboundDeliveryResult.rejected("invalid external Team delivery");
        }
        TeamMemberDefinition target = findMember(runtime.getDefinition(), teamMemberId);
        if (target == null || runtime.getDefinition().getState() != TeamState.READY) {
            return InboundDeliveryResult.rejected("Team member not ready");
        }
        synchronized (targetClient) {
            if (target.getState() == TeamMemberState.READY
                    && targetClient.getState() == AbstractAcpClient.State.READY) {
                stateObserver.onState(runtime.getDefinition().getTeamId(), teamMemberId,
                        TeamMemberState.BUSY, null);
                pushIncomingMessageCard(targetClient, message);
                targetClient.send(message.buildPrompt(), null);
                return InboundDeliveryResult.direct();
            }
        }
        if (target.getState() != TeamMemberState.BUSY
                && targetClient.getState() != AbstractAcpClient.State.BUSY) {
            return InboundDeliveryResult.rejected("Team member not busy or ready");
        }
        long now = clock.getAsLong();
        LinkedBlockingQueue<QueuedMessage> inbox = inboxes.computeIfAbsent(
                teamMemberId, ignored -> new LinkedBlockingQueue<>(INBOX_CAPACITY));
        purgeExpired(inbox, now);
        return inbox.offer(new QueuedMessage(UUID.randomUUID().toString(), message,
                        null, target, now + inboxTtlMillis))
                ? InboundDeliveryResult.queued(inbox.size())
                : InboundDeliveryResult.rejected("inbox full");
    }

    @Override
    public TalkToMessage pollInbox(String teamMemberId) {
        LinkedBlockingQueue<QueuedMessage> inbox = inboxes.get(teamMemberId);
        if (closed) return null;
        if (inbox == null) return null;
        long now = clock.getAsLong();
        purgeExpired(inbox, now);
        QueuedMessage queued = inbox.poll();
        if (queued == null) return null;
        stateObserver.onState(runtime.getDefinition().getTeamId(),
                queued.target.getTeamMemberId(), TeamMemberState.BUSY, null);
        if (queued.sender != null) {
            publish(queued.target, TeamEventType.TALK_TO_RECEIVE,
                    eventData(queued.messageId, queued.sender, queued.target,
                            queued.message.getContent(), queued.message.getDepth(),
                            "DELIVERED_FROM_INBOX", null, queued.expiresAt, null));
            publishCard(queued.target, queued.sender, TeamEventType.TALK_TO_RECEIVE,
                    queued.messageId, queued.message.getContent(),
                    "DELIVERED_FROM_INBOX", null);
        }
        return queued.message;
    }

    @Override
    public void pushIncomingMessageCard(AcpClient targetClient,
                                        TalkToMessage message) {
        // 队内卡片已在 direct deliver / pollInbox 时由 eventSink 发布。
        // 外部信道没有 Team member sender，必须按目标 member envelope 单独发布。
        if (!(message instanceof ChannelTalkToMessage) || targetClient == null) {
            return;
        }
        ChannelTalkToMessage channelMessage = (ChannelTalkToMessage) message;
        AcpClientIdentity identity = targetClient.getClientIdentity();
        if (identity != null && identity.isTeam()) {
            pushExternalTalkToCard(identity.getTeamMemberId(), "TALK_TO_RECEIVE",
                    "channel:" + channelMessage.getChannelDisplayName(),
                    channelMessage.getContent());
        }
    }

    private void pushExternalTalkToCard(String teamMemberId, String eventType,
                                        String target, String content) {
        TeamMemberDefinition member = findMember(runtime.getDefinition(), teamMemberId);
        if (member == null) return;
        TeamEventType type = "TALK_TO_RECEIVE".equals(eventType)
                ? TeamEventType.TALK_TO_RECEIVE : TeamEventType.TALK_TO_SEND;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", eventType);
        event.put("robotName", target);
        event.put("content", content == null ? "" : content);
        event.put("cardType", "CHANNEL_TALK_TO");
        event.put("direction", type == TeamEventType.TALK_TO_RECEIVE
                ? "RECEIVE" : "SEND");
        event.put("channelTarget", target);
        publish(member, type, event);

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("content", AcpResponseContentRenderer.talkToCardContent(
                eventType, target, content));
        card.put("cardType", "CHANNEL_TALK_TO");
        card.put("direction", event.get("direction"));
        card.put("channelTarget", target);
        publish(member, TeamEventType.MESSAGE_CHUNK, card);
    }

    private static String channelLabel(String target) {
        if (target == null) return "channel:unknown";
        int routeIndex = target.indexOf(":r_", "channel:".length());
        return routeIndex < 0 ? target : target.substring(0, routeIndex);
    }

    public int inboxSize(String teamMemberId) {
        LinkedBlockingQueue<QueuedMessage> inbox = inboxes.get(teamMemberId);
        if (inbox == null) {
            return 0;
        }
        purgeExpired(inbox, clock.getAsLong());
        return inbox.size();
    }

    public int dedupSize() {
        cleanExpiredDedup(clock.getAsLong());
        return recentMessages.size();
    }

    @Override
    public void close() {
        closed = true;
        inboxes.clear();
        recentMessages.clear();
    }

    private String reject(TeamMemberDefinition sender, String senderName,
                          TeamMemberDefinition target, String content, int depth,
                          String reason, String message) {
        if (sender != null) {
            String messageId = UUID.randomUUID().toString();
            publish(sender, TeamEventType.TALK_TO_REJECTED,
                    eventData(messageId, sender, target,
                            content, depth, "REJECTED", null, null, reason));
            if (target != null && !sender.getTeamMemberId().equals(
                    target.getTeamMemberId())) {
                publishCard(sender, target, TeamEventType.TALK_TO_REJECTED,
                        messageId, content,
                        "REJECTED", reason);
            }
        }
        return "[talkTo 结果]\n发送失败：" + message + "。";
    }

    private void purgeExpired(LinkedBlockingQueue<QueuedMessage> inbox,
                              long now) {
        QueuedMessage head;
        while ((head = inbox.peek()) != null && head.expiresAt <= now) {
            QueuedMessage expired = inbox.poll();
            if (expired != null && expired.sender != null) {
                publish(expired.sender, TeamEventType.TALK_TO_REJECTED,
                        eventData(expired.messageId, expired.sender,
                                expired.target, expired.message.getContent(),
                                expired.message.getDepth(), "EXPIRED", null,
                                expired.expiresAt, "TTL_EXPIRED"));
                publishCard(expired.sender, expired.target,
                        TeamEventType.TALK_TO_REJECTED,
                        expired.messageId,
                        expired.message.getContent(), "EXPIRED", "TTL_EXPIRED");
            }
        }
        // 不在这里移除空队列：deliver 可能刚通过 computeIfAbsent 创建它，
        // 随后仍要 offer。每 Team 最多 6 个 member，空队列由 close/delete 统一清理。
    }

    private void cleanExpiredDedup(long now) {
        recentMessages.entrySet().removeIf(
                entry -> now - entry.getValue() >= dedupWindowMillis);
    }

    private void publish(TeamMemberDefinition envelopeMember,
                         TeamEventType type, Map<String, Object> data) {
        if (envelopeMember == null || !runtime.isAcceptingRequests()) {
            return;
        }
        try {
            eventSink.publish(TeamEventEnvelope.next(runtime,
                    envelopeMember.getTeamMemberId(),
                    envelopeMember.getAcpClientId(), type, data));
        } catch (RuntimeException ignored) {
            // MolaChat 投影事件失败不改变 Team 内本地投递结果。
        }
    }

    private void publishCard(TeamMemberDefinition envelopeMember,
                             TeamMemberDefinition peer,
                             TeamEventType talkToType, String messageId,
                             String content, String delivery, String reason) {
        if (envelopeMember == null || peer == null
                || envelopeMember.getTeamMemberId().equals(
                peer.getTeamMemberId())) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", TeamTalkToCardRenderer.render(
                talkToType.name(), peer.getTeamMemberId(),
                peer.getDisplayName(), content, delivery, reason));
        data.put("cardType", "TEAM_TALK_TO");
        data.put("messageId", messageId);
        data.put("direction", talkToType == TeamEventType.TALK_TO_RECEIVE
                ? "RECEIVE" : "SEND");
        data.put("cardTargetTeamMemberId", peer.getTeamMemberId());
        data.put("delivery", delivery);
        if (reason != null) data.put("reason", reason);
        publish(envelopeMember, TeamEventType.MESSAGE_CHUNK, data);
    }

    private Map<String, Object> eventData(
            String messageId, TeamMemberDefinition sender,
            TeamMemberDefinition target, String content, int depth,
            String delivery, Integer queuePosition, Long expiresAt,
            String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", messageId);
        data.put("teamId", runtime.getDefinition().getTeamId());
        data.put("senderTeamMemberId",
                sender == null ? null : sender.getTeamMemberId());
        data.put("senderAcpClientId",
                sender == null ? null : sender.getAcpClientId());
        data.put("targetTeamMemberId",
                target == null ? null : target.getTeamMemberId());
        data.put("targetAcpClientId",
                target == null ? null : target.getAcpClientId());
        data.put("content", content);
        data.put("depth", depth);
        data.put("delivery", delivery);
        if (queuePosition != null) {
            data.put("queuePosition", queuePosition);
            data.put("queueCapacity", INBOX_CAPACITY);
        }
        if (expiresAt != null) {
            data.put("expiresAt", expiresAt);
        }
        if (reason != null) {
            data.put("reason", reason);
        }
        return data;
    }

    private static TeamMemberDefinition findMember(TeamDefinition team,
                                                   String teamMemberId) {
        if (teamMemberId == null) {
            return null;
        }
        for (TeamMemberDefinition member : team.getMembers()) {
            if (teamMemberId.equals(member.getTeamMemberId())) {
                return member;
            }
        }
        return null;
    }

    private static String contentDigest(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static final class QueuedMessage {
        private final String messageId;
        private final TalkToMessage message;
        private final TeamMemberDefinition sender;
        private final TeamMemberDefinition target;
        private final long expiresAt;

        private QueuedMessage(String messageId, TalkToMessage message,
                              TeamMemberDefinition sender,
                              TeamMemberDefinition target,
                              long expiresAt) {
            this.messageId = messageId;
            this.message = message;
            this.sender = sender;
            this.target = target;
            this.expiresAt = expiresAt;
        }
    }

    private static final class TeamTalkToMessage extends TalkToMessage {
        private final String messageId;

        private TeamTalkToMessage(String messageId, String sender,
                                  String content, int depth) {
            super(sender, content, depth);
            this.messageId = messageId;
        }

        @Override
        public String buildPrompt() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("📨 [Fast Team 路由消息] 发送者 memberId: ")
                    .append(getSender()).append("\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
            sb.append("以下消息由当前 Fast Team 的严格队内路由投递，发送者身份已经过验证：\n\n");
            sb.append(getContent()).append("\n\n");
            sb.append("─── 回复方式 ───\n");
            sb.append("如需回复，在正常文本末尾输出：\n");
            sb.append("{\"action\":\"talk_to\",\"target\":\"")
                    .append(getSender()).append("\",\"content\":\"你的回复内容\",\"_depth\":")
                    .append(getDepth()).append("}\n");
            return sb.toString();
        }
    }
}
