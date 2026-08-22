package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class DeepSeekHarnessAcpProviderTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void launchesPreinstalledDshAcpProfileWithoutRuntimeVersion() {
        DeepSeekHarnessAcpProvider provider = new DeepSeekHarnessAcpProvider();

        assertEquals(provider.managedRuntimeCommand("dsh").toString(), provider.getCommand());
        assertTrue(java.nio.file.Paths.get(provider.getCommand()).isAbsolute());
        assertArrayEquals(new String[]{"--profile", "acp"}, provider.getArgs());
        assertFalse(provider.hasFallbackCommand());
        assertEquals("session/close", provider.getSessionCloseMethod());
        assertTrue(provider.closeInputAfterSessionClose());
        assertEquals(".agents/skills", provider.getSkillsRelativePath());
    }

    @Test
    public void mapsRobotConfigurationToIsolatedDshEnvironment() {
        DeepSeekHarnessAcpProvider provider = new DeepSeekHarnessAcpProvider();
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("结算专家");
        robot.setApiKey("  ds-secret  ");
        robot.setDeepSeekBaseUrl(" https://gateway.example/v1 ");

        Map<String, String> env = provider.getExtraEnv(robot);

        assertEquals("ds-secret", env.get("DEEPSEEK_API_KEY"));
        assertEquals("https://gateway.example/v1", env.get("DEEPSEEK_BASE_URL"));
        assertEquals("workspace-write", env.get("DSH_PERMISSION_MODE"));
        assertTrue(env.get("DSH_HOME").replace('\\', '/').contains("/dsh/unicode-"));
    }

    @Test
    public void usesRobotModelAndSafePermissionDefault() {
        DeepSeekHarnessAcpProvider provider = new DeepSeekHarnessAcpProvider();
        AcpRobotParam robot = new AcpRobotParam();
        robot.setModel(" deepseek-v4-pro ");

        assertEquals("deepseek-v4-pro",
                provider.getInitialSessionConfigOptions(robot).get("model"));
        assertEquals(AgentProvider.PermissionPolicy.REJECT,
                provider.getPermissionPolicy(robot));

        robot.setPermissionPolicy("ALLOW_ONCE");
        assertEquals(AgentProvider.PermissionPolicy.ALLOW_ONCE,
                provider.getPermissionPolicy(robot));
    }

    @Test
    public void loadsCmdProxyGlobalAndProjectMcpConfiguration() {
        DeepSeekHarnessAcpProvider provider = new DeepSeekHarnessAcpProvider();

        List<Path> paths = provider.getMcpConfigPaths("/tmp/dsh-project");

        assertEquals(2, paths.size());
        assertEquals("mcp.json", paths.get(0).getFileName().toString());
        assertEquals("/tmp/dsh-project/.cmd-proxy/mcp.json",
                paths.get(1).toString().replace('\\', '/'));
    }

    @Test
    public void installsMissingRuntimeAndProfileOnlyOnce() throws Exception {
        Path runtimeHome = temporaryFolder.newFolder("runtime-home").toPath();
        Path dshHome = temporaryFolder.newFolder("dsh-home").toPath();
        AtomicBoolean dshAvailable = new AtomicBoolean(false);
        AtomicBoolean pnpmAvailable = new AtomicBoolean(false);
        List<List<String>> commands = Collections.synchronizedList(new ArrayList<>());

        DeepSeekHarnessRuntimeInstaller installer = new DeepSeekHarnessRuntimeInstaller(
                (command, path) -> available(command, "dsh", dshAvailable.get())
                        || available(command, "pnpm", pnpmAvailable.get())
                        || baseCommand(command).equals("npm"),
                (command, environment) -> {
                    commands.add(new ArrayList<>(command));
                    assertEquals(DeepSeekHarnessRuntimeInstaller.DEFAULT_NPM_REGISTRY,
                            environment.get("NPM_CONFIG_REGISTRY"));
                    assertEquals(DeepSeekHarnessRuntimeInstaller.DEFAULT_NPM_REGISTRY,
                            environment.get("npm_config_registry"));
                    assertTrue(environment.get("no_proxy").contains("registry.npmjs.org"));
                    assertEquals(environment.get("no_proxy"), environment.get("NO_PROXY"));
                    if (command.contains(DeepSeekHarnessRuntimeInstaller.DSH_PACKAGE)) {
                        dshAvailable.set(true);
                    }
                    if (command.contains("pnpm")) {
                        pnpmAvailable.set(true);
                    }
                    if (command.contains(DeepSeekHarnessRuntimeInstaller.ADAPTER_PACKAGE)) {
                        writeReadyProfile(dshHome);
                    }
                });

        Map<String, String> environment = Collections.singletonMap("PATH", "/mock/bin");
        installer.ensureInstalled(runtimeHome, dshHome, environment);
        installer.ensureInstalled(runtimeHome, dshHome, environment);

        assertEquals(2, commands.size());
        assertEquals(asPlatformCommand("npm"), commands.get(0).get(0));
        assertEquals(java.util.Arrays.asList("install", "--global", "--prefix",
                runtimeHome.toString(), "--registry",
                DeepSeekHarnessRuntimeInstaller.DEFAULT_NPM_REGISTRY,
                DeepSeekHarnessRuntimeInstaller.DSH_PACKAGE, "pnpm"),
                commands.get(0).subList(1, commands.get(0).size()));
        assertEquals(java.util.Arrays.asList(
                managedCommand(runtimeHome, "dsh").toString(), "plugin", "--profile",
                "acp", "add", DeepSeekHarnessRuntimeInstaller.ADAPTER_PACKAGE,
                "--registry", DeepSeekHarnessRuntimeInstaller.DEFAULT_NPM_REGISTRY),
                commands.get(1));
        assertTrue(installer.isProfileReady(dshHome));
    }

    @Test
    public void skipsEveryInstallCommandWhenRuntimeAndProfileAreReady() throws Exception {
        Path runtimeHome = temporaryFolder.newFolder("ready-runtime-home").toPath();
        Path dshHome = temporaryFolder.newFolder("ready-dsh-home").toPath();
        writeReadyProfile(dshHome);
        List<List<String>> commands = new ArrayList<>();
        DeepSeekHarnessRuntimeInstaller installer = new DeepSeekHarnessRuntimeInstaller(
                (command, path) -> available(command, "dsh", true),
                (command, environment) -> commands.add(new ArrayList<>(command)));

        installer.ensureInstalled(runtimeHome, dshHome,
                Collections.singletonMap("PATH", "/mock/bin"));

        assertTrue(commands.isEmpty());
    }

    @Test
    public void preservesExplicitNpmRegistryForRuntimeInstallation() throws Exception {
        Path runtimeHome = temporaryFolder.newFolder("registry-runtime-home").toPath();
        Path dshHome = temporaryFolder.newFolder("registry-dsh-home").toPath();
        AtomicBoolean dshAvailable = new AtomicBoolean(false);
        AtomicBoolean pnpmAvailable = new AtomicBoolean(false);
        String configuredRegistry = "https://registry.example.test/";

        DeepSeekHarnessRuntimeInstaller installer = new DeepSeekHarnessRuntimeInstaller(
                (command, path) -> available(command, "dsh", dshAvailable.get())
                        || available(command, "pnpm", pnpmAvailable.get())
                        || baseCommand(command).equals("npm"),
                (command, environment) -> {
                    assertEquals(configuredRegistry, environment.get("NPM_CONFIG_REGISTRY"));
                    assertEquals(configuredRegistry, environment.get("npm_config_registry"));
                    if (command.contains(DeepSeekHarnessRuntimeInstaller.DSH_PACKAGE)) {
                        dshAvailable.set(true);
                        pnpmAvailable.set(true);
                    }
                    if (command.contains(DeepSeekHarnessRuntimeInstaller.ADAPTER_PACKAGE)) {
                        writeReadyProfile(dshHome);
                    }
                });

        Map<String, String> environment = new java.util.HashMap<>();
        environment.put("PATH", "/mock/bin");
        environment.put("NPM_CONFIG_REGISTRY", configuredRegistry);
        installer.ensureInstalled(runtimeHome, dshHome, environment);
    }

    @Test
    public void serializesConcurrentFirstInstall() throws Exception {
        Path runtimeHome = temporaryFolder.newFolder("concurrent-runtime-home").toPath();
        Path dshHome = temporaryFolder.newFolder("concurrent-dsh-home").toPath();
        AtomicBoolean dshAvailable = new AtomicBoolean(false);
        AtomicBoolean pnpmAvailable = new AtomicBoolean(false);
        List<List<String>> commands = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstCommandStarted = new CountDownLatch(1);

        DeepSeekHarnessRuntimeInstaller installer = new DeepSeekHarnessRuntimeInstaller(
                (command, path) -> available(command, "dsh", dshAvailable.get())
                        || available(command, "pnpm", pnpmAvailable.get())
                        || baseCommand(command).equals("npm"),
                (command, environment) -> {
                    commands.add(new ArrayList<>(command));
                    if (command.contains(DeepSeekHarnessRuntimeInstaller.DSH_PACKAGE)) {
                        firstCommandStarted.countDown();
                        dshAvailable.set(true);
                        pnpmAvailable.set(true);
                    }
                    if (command.contains(DeepSeekHarnessRuntimeInstaller.ADAPTER_PACKAGE)) {
                        writeReadyProfile(dshHome);
                    }
                });
        Map<String, String> environment = Collections.singletonMap("PATH", "/mock/bin");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> runInstall(
                    installer, runtimeHome, dshHome, environment));
            firstCommandStarted.await();
            Future<?> second = executor.submit(() -> runInstall(
                    installer, runtimeHome, dshHome, environment));
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2, commands.size());
    }

    @Test(expected = IOException.class)
    public void failsStartupWhenRuntimeInstallFails() throws Exception {
        Path runtimeHome = temporaryFolder.newFolder("failed-runtime-home").toPath();
        Path dshHome = temporaryFolder.newFolder("failed-dsh-home").toPath();
        DeepSeekHarnessRuntimeInstaller installer = new DeepSeekHarnessRuntimeInstaller(
                (command, path) -> baseCommand(command).equals("npm"),
                (command, environment) -> {
                    throw new IOException("npm failed");
                });

        installer.ensureInstalled(runtimeHome, dshHome,
                Collections.singletonMap("PATH", "/mock/bin"));
    }

    private static void runInstall(DeepSeekHarnessRuntimeInstaller installer, Path runtimeHome,
                                   Path dshHome,
                                   Map<String, String> environment) {
        try {
            installer.ensureInstalled(runtimeHome, dshHome, environment);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeReadyProfile(Path dshHome) throws IOException {
        Path profile = dshHome.resolve("profiles").resolve("acp");
        Path installed = profile.resolve("node_modules").resolve("@openma")
                .resolve("deepseek-harness-acp");
        Files.createDirectories(installed);
        String manifest = "{\"dependencies\":{\"@openma/deepseek-harness-acp\":\"*\"},"
                + "\"dsh\":{\"profile\":{\"bundles\":[\"@deepseek-ai/dsh-base\","
                + "\"@openma/deepseek-harness-acp\"]}}}";
        Files.write(profile.resolve("package.json"), manifest.getBytes(StandardCharsets.UTF_8));
        Files.write(installed.resolve("package.json"), "{}".getBytes(StandardCharsets.UTF_8));
    }

    private static boolean available(String command, String expected, boolean available) {
        return available && baseCommand(command).equals(expected);
    }

    private static String baseCommand(String command) {
        return command.endsWith(".cmd") ? command.substring(0, command.length() - 4) : command;
    }

    private static String asPlatformCommand(String command) {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? command + ".cmd" : command;
    }

    private static Path managedCommand(Path runtimeHome, String command) {
        Path bin = System.getProperty("os.name").toLowerCase().contains("win")
                ? runtimeHome : runtimeHome.resolve("bin");
        return bin.resolve(asPlatformCommand(command));
    }
}
