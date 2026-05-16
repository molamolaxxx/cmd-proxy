package com.mola.cmd.proxy.app.acp.talkto;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.talkto.model.ContactRef;
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
        if (contacts == null || contacts.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n[通讯录]\n");
        sb.append("你可以通过 talk_to 指令向其他 robot 发送异步消息。");
        sb.append("消息发送后你不需要等待回复，可以继续当前工作。\n");
        sb.append("目标 robot 忙碌时消息会排队，对方空闲后自动收到。\n\n");

        sb.append("通讯录（仅供参考，你也可以向未列出的 robot 发送消息）：\n");
        int validCount = 0;
        for (ContactRef contact : contacts) {
            if (contact.getName() == null || contact.getName().isEmpty()) continue;
            if (contact.getName().equals(selfName)) continue;
            if (!robotRegistry.containsKey(contact.getName())) {
                logger.warn("通讯录引用 '{}' 在 robot 注册表中不存在，跳过", contact.getName());
                continue;
            }
            sb.append("- ").append(contact.getName());
            if (contact.getRemark() != null && !contact.getRemark().isEmpty()) {
                sb.append(": ").append(contact.getRemark());
            }
            sb.append("\n");
            validCount++;
        }

        if (validCount == 0) return "";

        sb.append("\n发送消息格式：\n");
        sb.append("{\"action\":\"talk_to\",\"target\":\"robot名称\",\"content\":\"消息内容\"}\n\n");

        sb.append("与 dispatch_subagent 的区别：\n");
        sb.append("- talk_to: 异步发送，不等待结果，目标在自己的上下文中处理\n");
        sb.append("- dispatch_subagent: 同步等待结果，创建临时进程执行\n");

        return sb.toString();
    }
}
