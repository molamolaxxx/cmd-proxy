package com.mola.cmd.proxy.app.acp.subagent;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.subagent.model.SubAgentRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * 子 Agent 上下文注入器。
 * <p>
 * 在主 Agent 的 sendPrompt() 中，将所有可用子 Agent 的能力描述
 * 注入到 prompt 前缀中，让 LLM 自主判断何时需要委托子 Agent。
 * <p>
 * 能力描述来源（优先级从高到低）：
 * <ol>
 *   <li>subAgents[].description（配置中的手动描述）</li>
 *   <li>目标 robot 的 ability.md（AbilityReflectionService 生成）</li>
 *   <li>目标 robot 的 signature（兜底）</li>
 * </ol>
 */
public class SubAgentContextInjector {

    private static final Logger logger = LoggerFactory.getLogger(SubAgentContextInjector.class);
    private static final String ABILITY_BASE_DIR =
            System.getProperty("user.home") + "/.cmd-proxy/ability";
    private static final String ABILITY_FILE = "ability.md";
    private static final int ABILITY_SUMMARY_MAX_CHARS = 800;

    /**
     * 构建子 Agent 能力描述文本，注入到主 Agent prompt 中。
     *
     * @param subAgentRefs  当前 robot 配置的 subAgents 列表
     * @param robotRegistry 全局 robot 注册表 (name -> AcpRobotParam)
     * @param selfName      主 Agent 自身的 robot name，用于检测 self-fork
     * @return 格式化的能力描述文本，无可用子 Agent 时返回 ""
     */
    public String buildContext(List<SubAgentRef> subAgentRefs,
                               Map<String, AcpRobotParam> robotRegistry,
                               String selfName) {
        if (subAgentRefs == null || subAgentRefs.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        int validCount = 0;
        List<String> validNames = new java.util.ArrayList<>();

        for (SubAgentRef ref : subAgentRefs) {
            AcpRobotParam targetRobot = robotRegistry.get(ref.getName());
            if (targetRobot == null) {
                logger.warn("子 Agent 引用 '{}' 在 robot 注册表中不存在，跳过", ref.getName());
                continue;
            }

            boolean isSelf = ref.getName().equals(selfName);

            sb.append("- 名称（name）: `").append(ref.getName()).append("`");
            if (isSelf) {
                sb.append(" ⚡ (self-fork: 你自己的并行分身，拥有相同的能力，适合并行处理独立子任务)");
            } else {
                String desc = resolveDescription(ref, targetRobot);
                sb.append("\n  能力: ").append(desc);
            }
            sb.append("\n\n");
            validNames.add(ref.getName());
            validCount++;
        }

        if (validCount == 0) return "";

        // 组装完整上下文
        StringBuilder result = new StringBuilder();
        result.append("\n[Available Sub-Agents]\n");
        result.append("你可以通过 dispatch_subagent 指令将任务委托给以下子 Agent。\n");
        result.append("当你判断某个子任务更适合由专业子 Agent 处理时，请使用此能力。\n");
        result.append("你可以同时派发多个子 Agent 并行执行。\n\n");
        result.append("## 可用子 Agent 列表\n\n");
        result.append(sb);

        // 明确列出合法名称
        result.append("## 重要：agent 字段必须使用上面列出的「名称（name）」\n");
        result.append("合法的 agent 名称只有以下 ").append(validNames.size()).append(" 个：");
        for (String name : validNames) {
            result.append(" `").append(name).append("`");
        }
        result.append("\n");
        result.append("不要使用能力描述、工具名、技能名等作为 agent 字段的值。\n\n");

        // 调用格式 + 示例
        result.append("## 调用格式\n");
        result.append("当你需要调用子 Agent 时，请在回复中输出以下 JSON（独占一行，不要包裹在代码块中）：\n");
        result.append("{\"action\":\"dispatch_subagent\",\"tasks\":[");
        result.append("{\"agent\":\"").append(validNames.get(0)).append("\",\"title\":\"简短任务名\",\"prompt\":\"具体任务描述\"}]}\n");
        result.append("其中 title 是任务的简短名称（2~6个字），用于在执行过程中区分同一 agent 的不同任务。\n\n");

        return result.toString();
    }

    /**
     * 按优先级解析子 Agent 的能力描述。
     */
    private String resolveDescription(SubAgentRef ref, AcpRobotParam targetRobot) {
        // 1. 配置中的手动描述
        if (ref.getDescription() != null && !ref.getDescription().isEmpty()) {
            return ref.getDescription();
        }

        // 2. ability.md
        String abilityContent = loadAbilityMd(targetRobot.getName());
        if (abilityContent != null && !abilityContent.isEmpty()) {
            return abilityContent;
        }

        // 3. 兜底 signature
        String sig = targetRobot.getSignature();
        return (sig != null && !sig.isEmpty()) ? sig : "（无能力描述）";
    }

    /**
     * 读取目标 robot 的 ability.md 文件。
     * 路径: ~/.cmd-proxy/ability/{robot-name-hash}/ability.md
     * <p>
     * 路径计算逻辑与 AbilityReflectionService 保持一致。
     */
    private String loadAbilityMd(String robotName) {
        try {
            Path abilityFile = Paths.get(ABILITY_BASE_DIR, hashName(robotName), ABILITY_FILE);
            if (!Files.exists(abilityFile)) return null;
            return new String(Files.readAllBytes(abilityFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("读取 ability.md 失败, robotName={}", robotName, e);
            return null;
        }
    }

    /**
     * 与 AbilityReflectionService.hashName() 保持一致的哈希算法。
     */
    private static String hashName(String name) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(name.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return name.replaceAll("[^a-zA-Z0-9]", "_");
        }
    }
}
