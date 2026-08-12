package com.mola.cmd.proxy.app.acp.talkto;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

public class ContactRemarkResolverTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void resolvesExplicitRemarkBeforeAbilityAndSignature() throws Exception {
        Path abilityBase = temporaryFolder.newFolder("ability").toPath();
        AcpRobotParam robot = robot("Target Robot", "signature");
        writeAbility(abilityBase, "Target-Robot", "ability summary");

        assertEquals("configured remark",
                ContactRemarkResolver.resolve("  configured remark  ", robot, abilityBase));
    }

    @Test
    public void resolvesAbilityBeforeSignature() throws Exception {
        Path abilityBase = temporaryFolder.newFolder("ability").toPath();
        AcpRobotParam robot = robot("Target Robot", "signature");
        writeAbility(abilityBase, "Target-Robot", "  ability summary  ");

        assertEquals("ability summary",
                ContactRemarkResolver.resolve("", robot, abilityBase));
    }

    @Test
    public void fallsBackToSignatureWhenAbilityIsBlankOrMissing() throws Exception {
        Path abilityBase = temporaryFolder.newFolder("ability").toPath();
        AcpRobotParam robot = robot("Target Robot", "  signature  ");
        writeAbility(abilityBase, "Target-Robot", "   \n");

        assertEquals("signature",
                ContactRemarkResolver.resolve(null, robot, abilityBase));
    }

    private static AcpRobotParam robot(String name, String signature) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName(name);
        robot.setSignature(signature);
        return robot;
    }

    private static void writeAbility(Path abilityBase, String directory, String content)
            throws Exception {
        Path robotDir = abilityBase.resolve(directory);
        Files.createDirectories(robotDir);
        Files.write(robotDir.resolve("ability.md"), content.getBytes(StandardCharsets.UTF_8));
    }
}
