package com.mola.cmd.proxy.app.acp.acpclient.agent;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NpmProviderRuntimeManagerTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

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

    @Test
    public void cleanupKeepsConfiguredAndAutoLatestVersions() throws Exception {
        Path providers = temporaryFolder.newFolder("providers").toPath();
        NpmProviderRuntimeManager testManager =
                new NpmProviderRuntimeManager(providers, 0L);
        Path root = providers.resolve("opencode");
        Files.createDirectories(root.resolve("1.0.0"));
        Files.createDirectories(root.resolve("2.0.0"));
        Files.createDirectories(root.resolve("3.0.0"));
        Files.write(root.resolve("default-version.txt"),
                "2.0.0\n".getBytes(StandardCharsets.UTF_8));

        com.mola.cmd.proxy.app.acp.AcpRobotParam disabledPinned = robot(
                false, AgentProviderType.OPENCODE, "1.0.0");
        com.mola.cmd.proxy.app.acp.AcpRobotParam autoLatest = robot(
                true, AgentProviderType.OPENCODE, null);
        testManager.updateConfiguredReferences(Arrays.asList(disabledPinned, autoLatest));

        testManager.cleanupUnusedInstalledVersions();

        assertTrue(Files.isDirectory(root.resolve("1.0.0")));
        assertTrue(Files.isDirectory(root.resolve("2.0.0")));
        assertFalse(Files.exists(root.resolve("3.0.0")));
        assertTrue(Files.isRegularFile(root.resolve("default-version.txt")));
    }

    @Test
    public void cleanupDefersActiveRuntimeUntilLeaseCloses() throws Exception {
        Path providers = temporaryFolder.newFolder("leased-providers").toPath();
        NpmProviderRuntimeManager testManager =
                new NpmProviderRuntimeManager(providers, 0L);
        Path runtime = providers.resolve("opencode").resolve("4.0.0");
        Files.createDirectories(runtime);
        testManager.updateConfiguredReferences(Collections.emptyList());

        AgentProvider.RuntimeLease lease = testManager.acquireRuntimeLease(
                runtime, AgentProviderType.OPENCODE, "4.0.0");
        testManager.cleanupUnusedInstalledVersions();
        assertTrue(Files.isDirectory(runtime));

        lease.close();
        testManager.cleanupUnusedInstalledVersions();
        assertFalse(Files.exists(runtime));
    }

    @Test
    public void cleanupGivesNewManualInstallTimeToBeSelected() throws Exception {
        Path providers = temporaryFolder.newFolder("grace-providers").toPath();
        NpmProviderRuntimeManager testManager = new NpmProviderRuntimeManager(
                providers, TimeUnit.HOURS.toMillis(24L));
        Path runtime = providers.resolve("opencode").resolve("5.0.0");
        Files.createDirectories(runtime);
        testManager.updateConfiguredReferences(Collections.emptyList());

        testManager.cleanupUnusedInstalledVersions();
        assertTrue(Files.isDirectory(runtime));

        Files.setLastModifiedTime(runtime, FileTime.fromMillis(
                System.currentTimeMillis() - TimeUnit.HOURS.toMillis(25L)));
        testManager.cleanupUnusedInstalledVersions();
        assertFalse(Files.exists(runtime));
    }

    @Test
    public void cleanupRemovesDanglingDefaultMarkerWhenNoRobotUsesProvider()
            throws Exception {
        Path providers = temporaryFolder.newFolder("unused-default-providers").toPath();
        NpmProviderRuntimeManager testManager =
                new NpmProviderRuntimeManager(providers, 0L);
        Path root = providers.resolve("opencode");
        Files.createDirectories(root.resolve("6.0.0"));
        Files.write(root.resolve("default-version.txt"),
                "6.0.0\n".getBytes(StandardCharsets.UTF_8));
        testManager.updateConfiguredReferences(Collections.emptyList());

        testManager.cleanupUnusedInstalledVersions();

        assertFalse(Files.exists(root.resolve("6.0.0")));
        assertFalse(Files.exists(root.resolve("default-version.txt")));
    }

    private com.mola.cmd.proxy.app.acp.AcpRobotParam robot(
            boolean enabled, AgentProviderType type, String version) {
        com.mola.cmd.proxy.app.acp.AcpRobotParam robot =
                new com.mola.cmd.proxy.app.acp.AcpRobotParam();
        robot.setEnabled(enabled);
        robot.setAgentProvider(type.name());
        robot.setProviderVersion(version);
        return robot;
    }

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
