package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek Harness 运行时的一次性安装器。
 * <p>
 * 以 npm global-prefix 形式将缺失的 dsh/pnpm 安装到 cmd-proxy 管理目录，
 * 然后在每个隔离的 DSH_HOME 中初始化一次 acp profile。
 * 同一 JVM 内的所有 Robot 共用一个安装锁，避免 npm/pnpm 并发解析同一依赖树。
 */
final class DeepSeekHarnessRuntimeInstaller {

    static final String ADAPTER_PACKAGE = "@openma/deepseek-harness-acp";
    static final String DSH_PACKAGE = "@deepseek-ai/dsh";
    static final String PROFILE_NAME = "acp";
    static final String DEFAULT_NPM_REGISTRY = "https://registry.npmjs.org/";

    private static final Logger logger =
            LoggerFactory.getLogger(DeepSeekHarnessRuntimeInstaller.class);
    private static final long INSTALL_TIMEOUT_MINUTES = 15;
    private static final int OUTPUT_TAIL_SIZE = 80;
    private static final List<String> FORWARDED_ENV_KEYS = Arrays.asList(
            "PATH", "HOME", "DSH_HOME",
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY",
            "http_proxy", "https_proxy", "no_proxy",
            "NPM_CONFIG_REGISTRY", "npm_config_registry"
    );

    interface CommandLocator {
        boolean isAvailable(String command, String pathValue);
    }

    interface CommandExecutor {
        void execute(List<String> command, Map<String, String> environment) throws IOException;
    }

    private final Object installLock = new Object();
    private final CommandLocator commandLocator;
    private final CommandExecutor commandExecutor;

    DeepSeekHarnessRuntimeInstaller() {
        this(new PathCommandLocator(), new ProcessCommandExecutor());
    }

    DeepSeekHarnessRuntimeInstaller(CommandLocator commandLocator,
                                    CommandExecutor commandExecutor) {
        this.commandLocator = commandLocator;
        this.commandExecutor = commandExecutor;
    }

    void ensureInstalled(Path runtimeHome, Path dshHome,
                         Map<String, String> environment) throws IOException {
        if (runtimeHome == null) {
            throw new IOException("无法准备 DeepSeek Harness：runtimeHome 为空");
        }
        if (dshHome == null) {
            throw new IOException("无法准备 DeepSeek Harness：DSH_HOME 为空");
        }
        if (environment == null) {
            throw new IOException("无法准备 DeepSeek Harness：子进程环境为空");
        }

        synchronized (installLock) {
            Map<String, String> installEnvironment = withInstallerDefaults(environment);
            String pathValue = installEnvironment.get("PATH");
            String managedPath = managedBin(runtimeHome).toString();
            boolean profileReady = isProfileReady(dshHome);
            List<String> missingPackages = new ArrayList<>();

            if (!commandLocator.isAvailable(platformCommand("dsh"), pathValue)) {
                missingPackages.add(DSH_PACKAGE);
            }
            // pnpm 只在 profile 尚未完成、确实需要执行 dsh plugin 时安装。
            if (!profileReady && !commandLocator.isAvailable(platformCommand("pnpm"), pathValue)) {
                missingPackages.add("pnpm");
            }

            if (!missingPackages.isEmpty()) {
                List<String> installCommand = new ArrayList<>();
                installCommand.add(platformCommand("npm"));
                installCommand.add("install");
                installCommand.add("--global");
                installCommand.add("--prefix");
                installCommand.add(runtimeHome.toString());
                installCommand.add("--registry");
                installCommand.add(installEnvironment.get("npm_config_registry"));
                installCommand.addAll(missingPackages);
                logger.info("DeepSeek Harness 运行时缺失，执行一次性安装: {}", installCommand);
                commandExecutor.execute(installCommand, installEnvironment);
            }

            requireCommand("dsh", pathValue, managedPath);
            if (profileReady) {
                logger.info("DeepSeek Harness ACP profile 已安装，跳过安装, DSH_HOME={}", dshHome);
                return;
            }

            requireCommand("pnpm", pathValue, managedPath);
            List<String> profileCommand = Arrays.asList(
                    managedCommand(runtimeHome, "dsh").toString(),
                    "plugin", "--profile", PROFILE_NAME,
                    "add", ADAPTER_PACKAGE,
                    "--registry", installEnvironment.get("npm_config_registry")
            );
            logger.info("初始化 DeepSeek Harness ACP profile: {}, DSH_HOME={}",
                    profileCommand, dshHome);
            commandExecutor.execute(profileCommand, installEnvironment);

            if (!isProfileReady(dshHome)) {
                throw new IOException("DeepSeek Harness ACP profile 安装命令已结束，但完整性校验失败: "
                        + profileDirectory(dshHome));
            }
            logger.info("DeepSeek Harness ACP profile 安装完成, DSH_HOME={}", dshHome);
        }
    }

    boolean isProfileReady(Path dshHome) {
        Path profile = profileDirectory(dshHome);
        Path manifest = profile.resolve("package.json");
        Path installedPackage = profile.resolve("node_modules")
                .resolve("@openma").resolve("deepseek-harness-acp").resolve("package.json");
        if (!Files.isRegularFile(manifest) || !Files.isRegularFile(installedPackage)) {
            return false;
        }

        try (BufferedReader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return false;
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonObject dependencies = object(root, "dependencies");
            JsonObject dsh = object(root, "dsh");
            JsonObject profileConfig = object(dsh, "profile");
            JsonArray bundles = array(profileConfig, "bundles");
            return dependencies != null
                    && dependencies.has(ADAPTER_PACKAGE)
                    && containsString(bundles, ADAPTER_PACKAGE);
        } catch (Exception e) {
            logger.warn("读取 DeepSeek Harness ACP profile 失败，将重新安装, manifest={}: {}",
                    manifest, e.getMessage());
            return false;
        }
    }

    private void requireCommand(String command, String pathValue, String managedPath)
            throws IOException {
        String platformCommand = platformCommand(command);
        if (!commandLocator.isAvailable(platformCommand, pathValue)
                && !commandLocator.isAvailable(platformCommand, managedPath)) {
            throw new IOException("DeepSeek Harness 安装完成后仍无法从 PATH 找到命令: "
                    + platformCommand);
        }
    }

    private Path profileDirectory(Path dshHome) {
        return dshHome.resolve("profiles").resolve(PROFILE_NAME);
    }

    private Path managedBin(Path runtimeHome) {
        return isWindows() ? runtimeHome : runtimeHome.resolve("bin");
    }

    private Path managedCommand(Path runtimeHome, String command) {
        return managedBin(runtimeHome).resolve(platformCommand(command));
    }

    private Map<String, String> withInstallerDefaults(Map<String, String> environment) {
        Map<String, String> result = new LinkedHashMap<>(environment);
        String registry = result.get("npm_config_registry");
        if (isBlank(registry)) {
            registry = result.get("NPM_CONFIG_REGISTRY");
        }
        boolean usingDefaultRegistry = false;
        if (isBlank(registry)) {
            // npm/pnpm otherwise fall back to ~/.npmrc. Old registry.npm.taobao.org
            // configurations are common on legacy hosts and now fail TLS negotiation.
            registry = DEFAULT_NPM_REGISTRY;
            usingDefaultRegistry = true;
        }
        // npm 与 pnpm 对环境键大小写的兼容行为不同，两者都注入同一有效值。
        result.put("NPM_CONFIG_REGISTRY", registry);
        result.put("npm_config_registry", registry);
        if (usingDefaultRegistry) {
            appendRegistryToNoProxy(result, registry);
        }
        return result;
    }

    private void appendRegistryToNoProxy(Map<String, String> environment, String registry) {
        try {
            String host = URI.create(registry).getHost();
            if (isBlank(host)) {
                return;
            }
            String configured = environment.get("no_proxy");
            if (isBlank(configured)) {
                configured = environment.get("NO_PROXY");
            }
            String effective = appendCsvValue(configured, host);
            environment.put("NO_PROXY", effective);
            environment.put("no_proxy", effective);
        } catch (IllegalArgumentException e) {
            logger.warn("无法解析 npm registry 主机，将保留现有代理设置: registry={}", registry);
        }
    }

    private String appendCsvValue(String configured, String value) {
        if (!isBlank(configured)) {
            for (String entry : configured.split(",")) {
                if (value.equalsIgnoreCase(entry.trim())) {
                    return configured;
                }
            }
            return configured + "," + value;
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static JsonObject object(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(name);
    }

    private static JsonArray array(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonArray()) {
            return null;
        }
        return parent.getAsJsonArray(name);
    }

    private static boolean containsString(JsonArray values, String expected) {
        if (values == null) {
            return false;
        }
        for (JsonElement value : values) {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    && expected.equals(value.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static String platformCommand(String command) {
        return isWindows() ? command + ".cmd" : command;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static final class PathCommandLocator implements CommandLocator {
        @Override
        public boolean isAvailable(String command, String pathValue) {
            if (pathValue == null || pathValue.trim().isEmpty()) {
                return false;
            }
            for (String directory : pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (directory == null || directory.trim().isEmpty()) {
                    continue;
                }
                Path candidate = Paths.get(directory.trim(), command);
                if (Files.isRegularFile(candidate)
                        && (isWindows() || Files.isExecutable(candidate))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class ProcessCommandExecutor implements CommandExecutor {
        @Override
        public void execute(List<String> command, Map<String, String> environment)
                throws IOException {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            for (String name : FORWARDED_ENV_KEYS) {
                String value = environment.get(name);
                if (value != null && !value.trim().isEmpty()) {
                    builder.environment().put(name, value);
                }
            }

            Process process = builder.start();
            Deque<String> outputTail = new ArrayDeque<>();
            Thread outputReader = new Thread(() -> drainOutput(process, command, outputTail),
                    "dsh-runtime-installer-output");
            outputReader.setDaemon(true);
            outputReader.start();

            boolean finished;
            try {
                finished = process.waitFor(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("DeepSeek Harness 安装被中断: " + command, e);
            }
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("DeepSeek Harness 安装超时 ("
                        + INSTALL_TIMEOUT_MINUTES + " 分钟): " + command);
            }
            try {
                outputReader.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.exitValue() != 0) {
                throw new IOException("DeepSeek Harness 安装失败 (exitCode="
                        + process.exitValue() + "): " + command + ", output=" + outputTail);
            }
        }

        private static void drainOutput(Process process, List<String> command,
                                        Deque<String> outputTail) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("[DSH INSTALL][{}] {}", command.get(0), line);
                    synchronized (outputTail) {
                        if (outputTail.size() >= OUTPUT_TAIL_SIZE) {
                            outputTail.removeFirst();
                        }
                        outputTail.addLast(line);
                    }
                }
            } catch (IOException e) {
                if (process.isAlive()) {
                    logger.warn("读取 DeepSeek Harness 安装输出失败: {}", e.getMessage());
                }
            }
        }
    }
}
