package com.mola.cmd.proxy.app.acp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AcpRobotParamTest {

    @Test
    public void deepSeekHarnessUsesDeepSeekDefaultAvatar() {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setAgentProvider("DEEPSEEK_HARNESS_ACP");

        assertEquals("img/deepseek.png", robot.getAvatar());
    }

    @Test
    public void explicitAvatarOverridesDeepSeekDefaultAvatar() {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setAgentProvider("DEEPSEEK_HARNESS_ACP");
        robot.setAvatar("https://example.com/custom.png");

        assertEquals("https://example.com/custom.png", robot.getAvatar());
    }
}
