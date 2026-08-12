package com.mola.cmd.proxy.app.acp.talkto;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.common.PathUtils;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves a contact description consistently for Main ACP and Fast Team. */
public final class ContactRemarkResolver {

    private static final Logger logger = LoggerFactory.getLogger(ContactRemarkResolver.class);
    private static final String ABILITY_FILE = "ability.md";
    private static final int ABILITY_SUMMARY_MAX_CHARS = 200;

    private ContactRemarkResolver() {
    }

    /** Priority: explicit contact/member remark, target ability.md summary, target signature. */
    public static String resolve(String explicitRemark, AcpRobotParam targetRobot) {
        return resolve(explicitRemark, targetRobot, CmdProxyHome.resolve("ability"));
    }

    static String resolve(String explicitRemark, AcpRobotParam targetRobot, Path abilityBaseDir) {
        String explicit = trimToNull(explicitRemark);
        if (explicit != null) return explicit;
        if (targetRobot == null) return "";

        String ability = loadAbilityMd(abilityBaseDir, targetRobot.getName());
        if (ability != null) return truncateAbility(ability);

        String signature = trimToNull(targetRobot.getSignature());
        return signature == null ? "" : signature;
    }

    private static String loadAbilityMd(Path abilityBaseDir, String robotName) {
        String normalizedName = trimToNull(robotName);
        if (normalizedName == null) return null;
        Path abilityFile = abilityBaseDir.resolve(PathUtils.sanitizePath(normalizedName))
                .resolve(ABILITY_FILE);
        if (!Files.isRegularFile(abilityFile)) return null;
        try {
            String content = new String(Files.readAllBytes(abilityFile), StandardCharsets.UTF_8);
            return trimToNull(content);
        } catch (IOException e) {
            logger.warn("读取 ability.md 失败, robotName={}", normalizedName, e);
            return null;
        }
    }

    private static String truncateAbility(String content) {
        if (content.length() <= ABILITY_SUMMARY_MAX_CHARS) return content.trim();
        int cutoff = content.lastIndexOf("\n", ABILITY_SUMMARY_MAX_CHARS);
        if (cutoff <= 0) cutoff = ABILITY_SUMMARY_MAX_CHARS;
        return content.substring(0, cutoff).trim() + "...";
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
