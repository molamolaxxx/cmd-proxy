package com.mola.cmd.proxy.app.acp.talkto;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.talkto.model.ContactRef;
import com.mola.cmd.proxy.app.acp.talkto.model.ExternalTalkToContact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * TalkTo 上下文注入器。
 * <p>
 * 在主 Agent 的 sendPrompt() 中，将通讯录信息和 talkTo 能力描述
 * 注入到 prompt 前缀中，让 LLM 知道可以联系哪些 robot。
 * <p>
 * 注入条件：robot 配置了 contacts（非空）时才注入。
 */
public class TalkToContextInjector {

    private static final Logger logger = LoggerFactory.getLogger(TalkToContextInjector.class);
    private final ExternalTalkToContactProvider externalContactProvider;
    private final String groupId;

    public TalkToContextInjector() {
        this(null, null);
    }

    public TalkToContextInjector(ExternalTalkToContactProvider externalContactProvider,
                                 String groupId) {
        this.externalContactProvider = externalContactProvider;
        this.groupId = groupId;
    }

    /**
     * 构建通讯录上下文，注入到 prompt 中。
     *
     * @param contacts      当前 robot 的通讯录配置（可为 null）
     * @param robotRegistry 全局 robot 注册表
     * @param selfName      当前 robot 名称
     * @return 格式化的上下文文本，无通讯录时返回 ""
     */
    public String buildContext(List<ContactRef> contacts,
                               Map<String, AcpRobotParam> robotRegistry,
                               String selfName) {

        StringBuilder sb = new StringBuilder();
        sb.append("\n<agent-team>\n");
        sb.append("你是 Agent 团队的一员。你可以通过 cmd-proxy MCP 的 talk_to 工具向团队中的其他 Agent 发送异步消息。");
        sb.append("消息发送后你不需要等待回复，可以继续当前工作。\n");
        sb.append("目标 Agent 忙碌时消息会排队，对方空闲后自动收到。\n\n");
        appendRuntimeConstraints(sb);

        String firstContactName = null;
        boolean hasContacts = false;
        if (contacts != null && !contacts.isEmpty()) {
            sb.append("你的团队成员（仅供参考，你也可以向未列出的 Agent 发送消息）：\n");
            for (ContactRef contact : contacts) {
                if (contact.getName() == null || contact.getName().isEmpty()) continue;
                if (contact.getName().equals(selfName)) continue;

                if (contact.isRemote()) {
                    sb.append("- ").append(contact.getName());
                    if (contact.getRemark() != null && !contact.getRemark().isEmpty()) {
                        sb.append(": ").append(contact.getRemark());
                    }
                    sb.append("\n");
                    if (firstContactName == null) firstContactName = contact.getName();
                    hasContacts = true;
                } else {
                    if (!robotRegistry.containsKey(contact.getName())) {
                        logger.warn("通讯录引用 '{}' 在 robot 注册表中不存在，跳过", contact.getName());
                        continue;
                    }
                    sb.append("- ").append(contact.getName());
                    String description = ContactRemarkResolver.resolve(
                            contact.getRemark(), robotRegistry.get(contact.getName()));
                    if (description != null && !description.isEmpty()) {
                        sb.append(": ").append(description);
                    }
                    sb.append("\n");
                    if (firstContactName == null) firstContactName = contact.getName();
                    hasContacts = true;
                }
            }
        }

        if (externalContactProvider != null && groupId != null) {
            List<ExternalTalkToContact> externalContacts =
                    externalContactProvider.contactsForGroup(groupId);
            if (externalContacts != null) {
                for (ExternalTalkToContact contact : externalContacts) {
                    if (contact == null || contact.getTarget() == null
                            || contact.getTarget().trim().isEmpty()) continue;
                    if (!hasContacts) {
                        sb.append("你的通讯录成员：\n");
                    }
                    sb.append("- ").append(contact.getDisplayName())
                            .append("（target: ").append(contact.getTarget()).append("）");
                    if (contact.getRemark() != null && !contact.getRemark().isEmpty()) {
                        sb.append(": ").append(contact.getRemark());
                    }
                    sb.append("\n");
                    if (firstContactName == null) firstContactName = contact.getTarget();
                    hasContacts = true;
                }
            }
        }

        if (hasContacts) {
            sb.append("\n与 dispatch_subagent 的区别：\n");
            sb.append("- talk_to: 异步发送，不等待结果，目标在自己的上下文中处理\n");
            sb.append("- dispatch_subagent: 同步等待结果，创建临时进程执行\n");
            sb.append("\n");
            sb.append("发送消息时直接调用 talk_to MCP 工具，并从上方通讯录选择准确 target。\n");
        } else {
            sb.append("你的通讯录为空，但其他已注册的 Agent 仍可能通过 ACP harness 向你发送消息，这是正常的系统行为。\n");
            sb.append("收到消息时请正常阅读、处理并回复。\n\n");
            sb.append("回复时调用 talk_to MCP 工具。\n");
            sb.append("注意：target 必须使用来信中标注的发送者名称。\n");
        }
        sb.append("不要在回复正文中模拟工具调用或输出 Action JSON。\n");

        sb.append("</agent-team>\n");
        return sb.toString();
    }

    /**
     * 追加普通与 Fast Team talkTo 必须保持一致的运行时约束。
     *
     * <p>Team 注入器会覆写 {@link #buildContext(List, Map, String)} 以隔离通讯录和
     * 路由身份，因此约束集中在这里复用，避免两种模式的安全语义发生漂移。</p>
     */
    protected static void appendRuntimeConstraints(StringBuilder sb) {
        sb.append("重要运行时约束：发出 talk_to 后，不要使用 Bash、PowerShell、Python 或其他脚本通过 wait、sleep、while 循环、轮询文件/日志/进程状态等方式等待对方回复。"
                + "这类等待会占用当前 turn；在等待脚本结束前，已入队的 Agent 消息无法被处理。\n\n");
        sb.append("补充语义：talk_to 的“已发送”或“已入队”仅表示路由层已接收消息，不表示接收方已处理，也不表示你会在当前 turn 内获得回复。\n\n");
    }

}
