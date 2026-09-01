package com.mola.cmd.proxy.app.acp.acpclient.agent;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NpmProviderRuntimeManagerTest {

    private final NpmProviderRuntimeManager manager =
            NpmProviderRuntimeManager.getInstance();

    @Test
    public void managesOnlyProvidersThatHaveAnNpmDistribution() {
        assertTrue(manager.supports(AgentProviderType.OPENCODE));
        assertTrue(manager.supports(AgentProviderType.CLAUDE_AGENT_ACP));
        assertTrue(manager.supports(AgentProviderType.CODEX_ACP));
        assertTrue(manager.supports(AgentProviderType.DEEPSEEK_HARNESS_ACP));
        assertFalse(manager.supports(AgentProviderType.KIRO_CLI));
        assertTrue(manager.supportsManagedInstall(AgentProviderType.CODEX_ACP));
        assertTrue(manager.supportsManagedInstall(AgentProviderType.OPENCODE));
    }

    @Test
    public void autoLatestRequiresAnEnabledRobotWithoutAnExactVersion() {
        com.mola.cmd.proxy.app.acp.AcpRobotParam robot =
                new com.mola.cmd.proxy.app.acp.AcpRobotParam();
        robot.setEnabled(true);
        robot.setProviderVersion(null);
        assertTrue(NpmProviderRuntimeManager.followsAutoLatest(robot));

        robot.setProviderVersion("1.2.3");
        assertFalse(NpmProviderRuntimeManager.followsAutoLatest(robot));

        robot.setProviderVersion(null);
        robot.setEnabled(false);
        assertFalse(NpmProviderRuntimeManager.followsAutoLatest(robot));
    }

    @Test
    public void isolatesExactVersionsAndRejectsMutableVersionSpecs() {
        Path first = manager.runtimeHome(AgentProviderType.OPENCODE, "1.2.3");
        Path second = manager.runtimeHome(AgentProviderType.OPENCODE, "1.2.4");

        assertNotEquals(first, second);
        assertTrue(first.toString().replace('\\', '/').endsWith("/opencode/1.2.3"));

        for (String invalid : new String[]{"latest", "^1.2.3", "1.2", ""}) {
            try {
                manager.runtimeHome(AgentProviderType.OPENCODE, invalid);
                fail("expected exact-version validation for " + invalid);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("exact provider version"));
            }
        }
    }

    @Test
    public void forcesNpmToInstallPlatformOptionalDependencies() {
        List<String> arguments = NpmProviderRuntimeManager.npmInstallArguments(
                Paths.get("runtime"), "https://registry.npmjs.org/", "package@1.2.3");

        assertTrue(arguments.contains("--include=optional"));
        assertEquals("package@1.2.3", arguments.get(arguments.size() - 1));
    }

    @Test
    public void resolvesCodexWindowsPlatformPackageFromOsAndArchitecture() throws Exception {
        String oldOsName = System.getProperty("os.name");
        String oldOsArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "amd64");
            assertEquals("@openai/codex-win32-x64",
                    NpmProviderRuntimeManager.requiredCodexPlatformPackage());

            System.setProperty("os.arch", "aarch64");
            assertEquals("@openai/codex-win32-arm64",
                    NpmProviderRuntimeManager.requiredCodexPlatformPackage());
        } finally {
            restoreProperty("os.name", oldOsName);
            restoreProperty("os.arch", oldOsArch);
        }
    }

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
