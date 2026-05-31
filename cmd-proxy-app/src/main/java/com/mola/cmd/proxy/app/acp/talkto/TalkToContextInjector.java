package com.mola.cmd.proxy.app.acp.talkto;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.common.DirectJsonOutputHelper;
import com.mola.cmd.proxy.app.acp.common.PathUtils;
import com.mola.cmd.proxy.app.acp.talkto.model.ContactRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private static final String ABILITY_BASE_DIR =
            System.getProperty("user.home") + "/.cmd-proxy/ability";
    private static final String ABILITY_FILE = "ability.md";
    private static final int ABILITY_SUMMARY_MAX_CHARS = 200;

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
        sb.append("\n[Agent Team]\n");
        sb.append("你是 Agent 团队的一员。你可以通过 talk_to 指令向团队中的其他 Agent 发送异步消息。");
        sb.append("消息发送后你不需要等待回复，可以继续当前工作。\n");
        sb.append("目标 Agent 忙碌时消息会排队，对方空闲后自动收到。\n\n");

        sb.append("你的团队成员（仅供参考，你也可以向未列出的 Agent 发送消息）：\n");
        int validCount = 0;
        String firstContactName = null;
        for (ContactRef contact : contacts) {
            if (contact.getName() == null || contact.getName().isEmpty()) continue;
            if (contact.getName().equals(selfName)) continue;

            if (contact.isRemote()) {
                // 跨 chatter 联系人：不做本地 registry 校验，直接展示（remark 必填）
                sb.append("- ").append(contact.getName());
                if (contact.getRemark() != null && !contact.getRemark().isEmpty()) {
                    sb.append(": ").append(contact.getRemark());
                }
                sb.append("\n");
                if (firstContactName == null) firstContactName = contact.getName();
                validCount++;
            } else {
                // 本地联系人：需要在 registry 中存在
                if (!robotRegistry.containsKey(contact.getName())) {
                    logger.warn("通讯录引用 '{}' 在 robot 注册表中不存在，跳过", contact.getName());
                    continue;
                }
                sb.append("- ").append(contact.getName());
                String description = resolveDescription(contact, robotRegistry.get(contact.getName()));
                if (description != null && !description.isEmpty()) {
                    sb.append(": ").append(description);
                }
                sb.append("\n");
                if (firstContactName == null) firstContactName = contact.getName();
                validCount++;
            }
        }

        if (validCount == 0) return "";

        sb.append("\n与 dispatch_subagent 的区别：\n");
        sb.append("- talk_to: 异步发送，不等待结果，目标在自己的上下文中处理\n");
        sb.append("- dispatch_subagent: 同步等待结果，创建临时进程执行\n");
        sb.append("\n");
        sb.append("发送消息格式：\n");
        sb.append("{\"action\":\"talk_to\",\"target\":\"")
          .append(firstContactName != null ? firstContactName : "目标名称")
          .append("\",\"content\":\"你的消息内容\"}\n");

        DirectJsonOutputHelper.appendUsageWarning(sb,
                "发送 talk_to 消息",
                "拦截该 JSON 并将其路由到目标 Agent");

        return sb.toString();
    }

    /**
     * 按优先级解析联系人的描述。
     * 优先级：remark > ability.md 摘要 > signature
     */
    private String resolveDescription(ContactRef contact, AcpRobotParam targetRobot) {
        // 1. 配置中的 remark
        if (contact.getRemark() != null && !contact.getRemark().isEmpty()) {
            return contact.getRemark();
        }

        // 2. ability.md 摘要
        String abilityContent = loadAbilityMd(targetRobot.getName());
        if (abilityContent != null && !abilityContent.isEmpty()) {
            return truncateAbility(abilityContent);
        }

        // 3. 兜底 signature
        String sig = targetRobot.getSignature();
        return (sig != null && !sig.isEmpty()) ? sig : null;
    }

    /**
     * 截断 ability 内容，取前 ABILITY_SUMMARY_MAX_CHARS 字符作为摘要。
     */
    private String truncateAbility(String content) {
        if (content.length() <= ABILITY_SUMMARY_MAX_CHARS) {
            return content.trim();
        }
        // 在限制范围内找最后一个换行
        int cutoff = content.lastIndexOf("\n", ABILITY_SUMMARY_MAX_CHARS);
        if (cutoff <= 0) {
            cutoff = ABILITY_SUMMARY_MAX_CHARS;
        }
        return content.substring(0, cutoff).trim() + "...";
    }

    /**
     * 读取目标 robot 的 ability.md 文件。
     * 路径: ~/.cmd-proxy/ability/{sanitized-robotName}/ability.md
     */
    private String loadAbilityMd(String robotName) {
        if (robotName == null || robotName.isEmpty()) return null;
        try {
            Path abilityFile = Paths.get(ABILITY_BASE_DIR, PathUtils.sanitizePath(robotName), ABILITY_FILE);
            if (!Files.exists(abilityFile)) return null;
            return new String(Files.readAllBytes(abilityFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("读取 ability.md 失败, robotName={}", robotName, e);
            return null;
        }
    }
}
