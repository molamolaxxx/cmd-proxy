package com.mola.cmd.proxy.app.acp.acpclient.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.common.PathResolver;
import com.mola.cmd.proxy.app.utils.CmdProxyHome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Versioned runtime store for providers distributed through npm.
 *
 * <p>Each exact package version is installed into an isolated prefix and is always
 * launched through an absolute executable path. Existing versions are immutable;
 * installing another version never modifies a running provider process.</p>
 */
public final class NpmProviderRuntimeManager {

    private static final Logger logger =
            LoggerFactory.getLogger(NpmProviderRuntimeManager.class);

    public static final String ENV_EXECUTABLE = "CMD_PROXY_PROVIDER_EXECUTABLE";
    public static final String ENV_VERSION = "CMD_PROXY_PROVIDER_RUNTIME_VERSION";
    public static final String DEFAULT_REGISTRY = "https://registry.npmjs.org/";

    private static final Pattern EXACT_VERSION = Pattern.compile(
            "[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?(?:\\+[0-9A-Za-z.-]+)?");
    private static final long INSTALL_TIMEOUT_MINUTES = 15L;
    private static final int MOVE_RETRY_COUNT = 8;
    private static final int MAX_CATALOG_VERSIONS = 100;
    private static final long AUTO_LATEST_INTERVAL_HOURS = 6L;
    private static final NpmProviderRuntimeManager INSTANCE =
            new NpmProviderRuntimeManager();

    private final Map<AgentProviderType, Distribution> distributions =
            new EnumMap<>(AgentProviderType.class);
    private final ConcurrentHashMap<String, Object> installLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, InstallJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService installerExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "provider-runtime-installer");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService autoLatestExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "provider-auto-latest-updater");
                thread.setDaemon(true);
                return thread;
            });
    private volatile Map<AgentProviderType, List<Map<String, String>>> autoLatestTargets =
            Collections.emptyMap();
    private volatile boolean autoLatestStarted;

    private NpmProviderRuntimeManager() {
        register(new Distribution(AgentProviderType.OPENCODE,
                "opencode", "opencode-ai", "opencode"));
        register(new Distribution(AgentProviderType.CLAUDE_AGENT_ACP,
                "claude-agent-acp", "@agentclientprotocol/claude-agent-acp",
                "claude-agent-acp"));
        register(new Distribution(AgentProviderType.CODEX_ACP,
                "codex-acp", "@agentclientprotocol/codex-acp", "codex-acp"));
        register(new Distribution(AgentProviderType.DEEPSEEK_HARNESS_ACP,
                "deepseek-harness", "@deepseek-ai/dsh", "dsh"));
    }

    public static NpmProviderRuntimeManager getInstance() {
        return INSTANCE;
    }

    public boolean supports(AgentProviderType type) {
        return distributions.containsKey(type);
    }

    public boolean supportsManagedInstall(AgentProviderType type) {
        return supports(type);
    }

    /**
     * Replaces the set of configured providers that follow npm's mutable latest tag.
     * Registry access and installation happen only on the dedicated background executor;
     * this method never blocks ACP startup.
     */
    public synchronized void configureAutoLatest(Collection<AcpRobotParam> robots) {
        Map<AgentProviderType, List<Map<String, String>>> targets = new EnumMap<>(
                AgentProviderType.class);
        if (robots != null) {
            for (AcpRobotParam robot : robots) {
                if (!followsAutoLatest(robot)) {
                    continue;
                }
                AgentProviderType type = AgentProviderType.fromString(
                        robot.getAgentProvider());
                if (!supports(type)) continue;
                targets.computeIfAbsent(type, ignored -> new ArrayList<>())
                        .add(installerEnvironment(robot));
            }
        }
        Map<AgentProviderType, List<Map<String, String>>> immutable =
                new EnumMap<>(AgentProviderType.class);
        for (Map.Entry<AgentProviderType, List<Map<String, String>>> entry
                : targets.entrySet()) {
            immutable.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        autoLatestTargets = Collections.unmodifiableMap(immutable);
        if (!autoLatestStarted) {
            autoLatestStarted = true;
            autoLatestExecutor.scheduleWithFixedDelay(this::updateConfiguredLatestSafely,
                    AUTO_LATEST_INTERVAL_HOURS, AUTO_LATEST_INTERVAL_HOURS, TimeUnit.HOURS);
        }
        autoLatestExecutor.execute(this::updateConfiguredLatestSafely);
        logger.info("Provider 自动 latest 后台任务已配置, providers={}",
                autoLatestTargets.keySet());
    }

    public void prepareLaunch(AgentProviderType type, AcpRobotParam robot,
                              Map<String, String> environment) throws IOException {
        RuntimeInstall installed = ensureInstalled(type,
                robot == null ? null : robot.getProviderVersion(), environment);
        environment.put(ENV_EXECUTABLE, installed.executable.toString());
        environment.put(ENV_VERSION, installed.version);
        prependPath(environment, installed.executable.getParent().toString());
    }

    public String preparedCommand(Map<String, String> environment) {
        String executable = environment == null ? null : environment.get(ENV_EXECUTABLE);
        if (executable == null || executable.trim().isEmpty()) {
            throw new IllegalStateException("provider runtime executable was not prepared");
        }
        return executable;
    }

    public Path runtimeHome(AgentProviderType type, String exactVersion) {
        Distribution distribution = requireDistribution(type);
        requireExactVersion(exactVersion);
        return providerRoot(distribution).resolve(exactVersion).toAbsolutePath().normalize();
    }

    public ReleaseCatalog fetchReleases(AgentProviderType type) throws IOException {
        Distribution distribution = requireDistribution(type);
        JsonObject root = readRegistryMetadata(distribution.packageName);
        Map<String, String> distTags = new LinkedHashMap<>();
        JsonObject tags = root.getAsJsonObject("dist-tags");
        if (tags != null) {
            for (Map.Entry<String, JsonElement> entry : tags.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    distTags.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        List<String> versions = new ArrayList<>();
        JsonObject versionObject = root.getAsJsonObject("versions");
        if (versionObject != null) {
            for (String version : versionObject.keySet()) {
                if (isExactVersion(version)) versions.add(version);
            }
        }
        versions.sort(VERSION_DESCENDING);
        if (versions.size() > MAX_CATALOG_VERSIONS) {
            versions = new ArrayList<>(versions.subList(0, MAX_CATALOG_VERSIONS));
        }
        return new ReleaseCatalog(type, distribution.packageName,
                Collections.unmodifiableMap(distTags), Collections.unmodifiableList(versions),
                installedVersions(type));
    }

    public RuntimeStatus status(AgentProviderType type) {
        Distribution distribution = requireDistribution(type);
        RuntimeInstall defaultInstall = readDefaultInstall(distribution);
        return new RuntimeStatus(type, distribution.packageName, installedVersions(type),
                defaultInstall == null ? null : defaultInstall.version);
    }

    public InstallJob startInstall(AgentProviderType type, String exactVersion,
                                   Map<String, String> environment) {
        requireDistribution(type);
        requireExactVersion(exactVersion);
        String jobId = UUID.randomUUID().toString();
        InstallJob job = new InstallJob(jobId, type, exactVersion);
        jobs.put(jobId, job);
        Map<String, String> copiedEnvironment = environment == null
                ? Collections.emptyMap() : new LinkedHashMap<>(environment);
        installerExecutor.submit(() -> {
            try {
                job.update("RESOLVING", 10, "正在核验发行版本");
                verifyPublishedVersion(type, exactVersion);
                job.update("INSTALLING", 35, "正在安装 npm package");
                RuntimeInstall installed = ensureExactInstalled(
                        requireDistribution(type), exactVersion, copiedEnvironment);
                job.update("VERIFYING", 85, "正在核验实际安装版本");
                verifyInstalled(requireDistribution(type), installed.home, exactVersion);
                job.update("DONE", 100, "版本已安装，保存并刷新 Robot 后生效");
            } catch (Exception e) {
                job.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
        return job.snapshot();
    }

    public InstallJob job(String jobId) {
        InstallJob job = jobs.get(jobId);
        return job == null ? null : job.snapshot();
    }

    RuntimeInstall ensureInstalled(AgentProviderType type, String requestedVersion,
                                   Map<String, String> environment) throws IOException {
        Distribution distribution = requireDistribution(type);
        String version = trimToNull(requestedVersion);
        if (version == null) {
            RuntimeInstall installed = readDefaultInstall(distribution);
            if (installed == null) {
                throw new IOException("automatic latest runtime is not ready for "
                        + distribution.packageName
                        + "; wait for the background provider updater or install a version in ConfigUI");
            }
            return installed;
        }
        requireExactVersion(version);
        RuntimeInstall installed = ensureExactInstalled(distribution, version, environment);
        return installed;
    }

    private void updateConfiguredLatestSafely() {
        Map<AgentProviderType, List<Map<String, String>>> snapshot = autoLatestTargets;
        for (Map.Entry<AgentProviderType, List<Map<String, String>>> entry
                : snapshot.entrySet()) {
            try {
                updateLatest(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                logger.warn("Provider 自动 latest 更新失败, provider={}", entry.getKey(), e);
            }
        }
    }

    private void updateLatest(AgentProviderType type,
                              List<Map<String, String>> environments) throws IOException {
        Distribution distribution = requireDistribution(type);
        String latest = fetchReleases(type).getDistTags().get("latest");
        if (latest == null || latest.trim().isEmpty()) {
            throw new IOException("npm registry did not publish a latest version for "
                    + distribution.packageName);
        }
        requireExactVersion(latest);
        RuntimeInstall current = readDefaultInstall(distribution);
        if (current != null && latest.equals(current.version)) {
            logger.debug("Provider latest 已是当前默认版本, provider={}, version={}",
                    type, latest);
            return;
        }

        IOException lastError = null;
        List<Map<String, String>> candidates = environments == null
                ? Collections.emptyList() : environments;
        if (candidates.isEmpty()) {
            candidates = Collections.singletonList(installerEnvironment(null));
        }
        for (Map<String, String> environment : candidates) {
            try {
                RuntimeInstall installed = ensureExactInstalled(
                        distribution, latest, prepareInstallerEnvironment(environment));
                verifyInstalled(distribution, installed.home, latest);
                writeDefaultVersion(distribution, latest);
                logger.info("Provider 自动 latest 已准备完成, provider={}, previous={}, latest={}; 下次 ACP 启动生效",
                        type, current == null ? null : current.version, latest);
                return;
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw lastError == null ? new IOException("no installer environment available")
                : lastError;
    }

    private Map<String, String> installerEnvironment(AcpRobotParam robot) {
        Map<String, String> environment = new LinkedHashMap<>(System.getenv());
        if (robot != null && robot.isProxyEnabled()) {
            String proxy = trimToNull(robot.getHttpProxy());
            if (proxy != null) {
                String url = proxy.contains("://") ? proxy : "http://" + proxy;
                environment.put("HTTP_PROXY", url);
                environment.put("http_proxy", url);
                environment.put("HTTPS_PROXY", url);
                environment.put("https_proxy", url);
            }
            String noProxy = trimToNull(robot.getNoProxy());
            if (noProxy != null) {
                environment.put("NO_PROXY", noProxy);
                environment.put("no_proxy", noProxy);
            }
        }
        return environment;
    }

    private Map<String, String> prepareInstallerEnvironment(
            Map<String, String> source) {
        Map<String, String> environment = source == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
        String home = System.getProperty("user.home");
        environment.put("PATH", PathResolver.enrichPath(home,
                environment.get("PATH") == null ? "" : environment.get("PATH")));
        return environment;
    }

    private RuntimeInstall ensureExactInstalled(Distribution distribution, String version,
                                                Map<String, String> environment)
            throws IOException {
        Path target = providerRoot(distribution).resolve(version);
        if (isInstalled(distribution, target, version)) {
            return new RuntimeInstall(target, executable(distribution, target), version);
        }
        Object lock = installLocks.computeIfAbsent(distribution.runtimeKey + "@" + version,
                ignored -> new Object());
        synchronized (lock) {
            Path providerRoot = providerRoot(distribution);
            Files.createDirectories(providerRoot);
            Path lockFile = providerRoot.resolve(".install-" + version + ".lock");
            try (FileChannel lockChannel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock acquired = lockChannel.tryLock();
                if (acquired == null) {
                    throw new IOException("another cmd-proxy process is installing provider "
                            + distribution.type + "@" + version);
                }
                try (FileLock ignored = acquired) {
                    if (isInstalled(distribution, target, version)) {
                        return new RuntimeInstall(target, executable(distribution, target), version);
                    }
                    Path staging = providerRoot.resolve(
                            ".install-" + version + "-" + UUID.randomUUID());
                    try {
                        Files.createDirectories(staging);
                        runNpmInstall(distribution, version, staging, environment);
                        verifyInstalled(distribution, staging, version);
                        promoteInstalledDirectory(distribution, version, staging, target);
                    } catch (Exception e) {
                        deleteTree(staging);
                        if (e instanceof IOException) throw (IOException) e;
                        throw new IOException("provider runtime install failed: "
                                + e.getMessage(), e);
                    }
                    return new RuntimeInstall(target, executable(distribution, target), version);
                }
            } finally {
                installLocks.remove(distribution.runtimeKey + "@" + version, lock);
            }
        }
    }

    private void verifyPublishedVersion(AgentProviderType type, String version)
            throws IOException {
        Distribution distribution = requireDistribution(type);
        JsonObject metadata = readRegistryMetadata(distribution.packageName);
        JsonObject versions = metadata.getAsJsonObject("versions");
        if (versions == null || !versions.has(version)) {
            throw new IOException("npm version is not published: " + version);
        }
    }

    private void runNpmInstall(Distribution distribution, String version, Path prefix,
                               Map<String, String> environment) throws IOException {
        Map<String, String> effective = environment == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(environment);
        String registry = firstNonBlank(effective.get("npm_config_registry"),
                effective.get("NPM_CONFIG_REGISTRY"), DEFAULT_REGISTRY);
        String npm = locateCommand(platformCommand("npm"), effective.get("PATH"));
        List<String> command = new ArrayList<>();
        command.add(npm);
        command.addAll(npmInstallArguments(prefix, registry,
                distribution.packageName + "@" + version));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        copyEnvironment(effective, builder.environment());
        Process process = builder.start();
        Deque<String> tail = new ArrayDeque<>();
        Thread reader = new Thread(() -> drain(process, tail), "npm-provider-install-output");
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = process.waitFor(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("npm install interrupted", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("npm install timed out after " + INSTALL_TIMEOUT_MINUTES
                    + " minutes");
        }
        if (process.exitValue() != 0) {
            throw new IOException("npm install failed (exitCode=" + process.exitValue()
                    + "): " + tail);
        }
    }

    private JsonObject readRegistryMetadata(String packageName) throws IOException {
        String encoded = URLEncoder.encode(packageName, "UTF-8").replace("+", "%20");
        URL url = new URL(DEFAULT_REGISTRY + encoded);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", "application/json");
        int code = connection.getResponseCode();
        if (code != 200) {
            throw new IOException("npm registry returned HTTP " + code + " for " + packageName);
        }
        try (InputStream input = connection.getInputStream();
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IOException("npm registry response is not an object");
            }
            return parsed.getAsJsonObject();
        } finally {
            connection.disconnect();
        }
    }

    private List<String> installedVersions(AgentProviderType type) {
        Distribution distribution = requireDistribution(type);
        Path root = providerRoot(distribution);
        if (!Files.isDirectory(root)) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(root)) {
            for (Path directory : directories) {
                String version = directory.getFileName().toString();
                if (isExactVersion(version) && isInstalled(distribution, directory, version)) {
                    result.add(version);
                }
            }
        } catch (IOException ignored) {
            return Collections.emptyList();
        }
        result.sort(VERSION_DESCENDING);
        return Collections.unmodifiableList(result);
    }

    private void verifyInstalled(Distribution distribution, Path home, String version)
            throws IOException {
        Path manifest = packageManifest(distribution, home);
        if (!Files.isRegularFile(manifest)) {
            throw new IOException("installed package manifest is missing: " + manifest);
        }
        JsonObject json;
        try (BufferedReader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            json = JsonParser.parseReader(reader).getAsJsonObject();
        }
        String actual = json.has("version") ? json.get("version").getAsString() : "";
        if (!version.equals(actual)) {
            throw new IOException("installed version mismatch: expected=" + version
                    + ", actual=" + actual);
        }
        Path executable = executable(distribution, home);
        if (!Files.isRegularFile(executable)) {
            throw new IOException("provider executable is missing: " + executable);
        }
        if (!isWindows() && !Files.isExecutable(executable)) {
            throw new IOException("provider executable is not executable: " + executable);
        }
        if (distribution.type == AgentProviderType.CODEX_ACP) {
            verifyCodexPlatformPackage(distribution, home);
        }
    }

    private boolean isInstalled(Distribution distribution, Path home, String version) {
        try {
            verifyInstalled(distribution, home, version);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Path packageManifest(Distribution distribution, Path home) {
        return packageRoot(nodeModules(home), distribution.packageName).resolve("package.json");
    }

    private Path nodeModules(Path home) {
        return isWindows() ? home.resolve("node_modules")
                : home.resolve("lib").resolve("node_modules");
    }

    private Path packageRoot(Path nodeModules, String packageName) {
        Path packagePath = nodeModules;
        for (String segment : packageName.split("/")) {
            packagePath = packagePath.resolve(segment);
        }
        return packagePath;
    }

    private void verifyCodexPlatformPackage(Distribution distribution, Path home)
            throws IOException {
        String platformPackage = requiredCodexPlatformPackage();
        Path rootModules = nodeModules(home);
        Path adapterRoot = packageRoot(rootModules, distribution.packageName);
        List<Path> moduleRoots = Arrays.asList(
                rootModules,
                adapterRoot.resolve("node_modules"),
                packageRoot(adapterRoot.resolve("node_modules"), "@openai/codex")
                        .resolve("node_modules"));
        for (Path moduleRoot : moduleRoots) {
            if (Files.isRegularFile(packageRoot(moduleRoot, platformPackage)
                    .resolve("package.json"))) {
                return;
            }
        }
        throw new IOException("Codex platform package is missing: " + platformPackage
                + "; npm optional dependencies were not installed");
    }

    static String requiredCodexPlatformPackage() throws IOException {
        String os;
        if (isWindows()) {
            os = "win32";
        } else {
            String osName = System.getProperty("os.name", "").toLowerCase();
            if (osName.contains("mac") || osName.contains("darwin")) {
                os = "darwin";
            } else if (osName.contains("linux")) {
                os = "linux";
            } else {
                throw new IOException("unsupported Codex operating system: " + osName);
            }
        }
        String architecture = System.getProperty("os.arch", "").toLowerCase();
        String arch;
        if (architecture.equals("amd64") || architecture.equals("x86_64")) {
            arch = "x64";
        } else if (architecture.equals("aarch64") || architecture.equals("arm64")) {
            arch = "arm64";
        } else {
            throw new IOException("unsupported Codex architecture: " + architecture);
        }
        return "@openai/codex-" + os + "-" + arch;
    }

    private Path executable(Distribution distribution, Path home) {
        return (isWindows() ? home : home.resolve("bin"))
                .resolve(platformCommand(distribution.binName));
    }

    private Path providerRoot(Distribution distribution) {
        return CmdProxyHome.resolve("runtimes", "providers", distribution.runtimeKey)
                .toAbsolutePath().normalize();
    }

    private RuntimeInstall readDefaultInstall(Distribution distribution) {
        Path marker = providerRoot(distribution).resolve("default-version.txt");
        if (!Files.isRegularFile(marker)) return null;
        try {
            String version = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
            Path home = providerRoot(distribution).resolve(version);
            return isExactVersion(version) && isInstalled(distribution, home, version)
                    ? new RuntimeInstall(home, executable(distribution, home), version) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void writeDefaultVersion(Distribution distribution, String version)
            throws IOException {
        Path root = providerRoot(distribution);
        Files.createDirectories(root);
        Path marker = root.resolve("default-version.txt");
        Path temporary = root.resolve(".default-version-" + UUID.randomUUID());
        Files.write(temporary, (version + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void promoteInstalledDirectory(Distribution distribution, String version,
                                           Path source, Path target) throws IOException {
        if (Files.exists(target) && isInstalled(distribution, target, version)) {
            deleteTree(source);
            return;
        }
        Path displaced = null;
        if (Files.exists(target)) {
            displaced = target.resolveSibling(".invalid-" + version + "-" + UUID.randomUUID());
            moveDirectoryWithRetry(target, displaced);
        }
        try {
            moveDirectoryWithRetry(source, target);
        } catch (IOException installError) {
            if (displaced != null && Files.exists(displaced) && !Files.exists(target)) {
                try {
                    moveDirectoryWithRetry(displaced, target);
                } catch (IOException restoreError) {
                    installError.addSuppressed(restoreError);
                }
            }
            throw installError;
        }
        if (displaced != null) {
            deleteTree(displaced);
        }
    }

    private void moveDirectoryWithRetry(Path source, Path target) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < MOVE_RETRY_COUNT; attempt++) {
            try {
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(source, target);
                } catch (AccessDeniedException atomicDenied) {
                    // Some Windows filesystems/security filters reject an atomic directory
                    // rename even though a normal rename is permitted.
                    try {
                        Files.move(source, target);
                    } catch (IOException normalMoveError) {
                        normalMoveError.addSuppressed(atomicDenied);
                        throw normalMoveError;
                    }
                }
                return;
            } catch (FileAlreadyExistsException e) {
                throw e;
            } catch (FileSystemException e) {
                lastError = e;
                if (!isWindows() || attempt + 1 >= MOVE_RETRY_COUNT) {
                    throw e;
                }
                sleepBeforeMoveRetry(attempt);
            }
        }
        throw lastError == null ? new IOException("failed to move provider runtime directory")
                : lastError;
    }

    private void sleepBeforeMoveRetry(int attempt) throws IOException {
        long delayMillis = Math.min(2000L, 100L << Math.min(attempt, 4));
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("provider runtime directory move interrupted", e);
        }
    }

    private void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private void prependPath(Map<String, String> environment, String directory) {
        String current = environment.get("PATH");
        if (current == null || current.trim().isEmpty()) {
            environment.put("PATH", directory);
        } else if (!Arrays.asList(current.split(Pattern.quote(File.pathSeparator)))
                .contains(directory)) {
            environment.put("PATH", directory + File.pathSeparator + current);
        }
    }

    private void copyEnvironment(Map<String, String> source, Map<String, String> target) {
        for (String key : Arrays.asList("PATH", "HOME", "HTTP_PROXY", "HTTPS_PROXY",
                "NO_PROXY", "http_proxy", "https_proxy", "no_proxy",
                "NPM_CONFIG_REGISTRY", "npm_config_registry")) {
            String value = source.get(key);
            if (value != null && !value.trim().isEmpty()) target.put(key, value);
        }
    }

    private String locateCommand(String command, String pathValue) throws IOException {
        if (pathValue != null) {
            for (String directory : pathValue.split(Pattern.quote(File.pathSeparator))) {
                if (directory == null || directory.trim().isEmpty()) continue;
                Path candidate = new File(directory.trim(), command).toPath();
                if (Files.isRegularFile(candidate) && (isWindows() || Files.isExecutable(candidate))) {
                    return candidate.toAbsolutePath().normalize().toString();
                }
            }
        }
        throw new IOException("cannot find " + command + " in PATH");
    }

    private static void drain(Process process, Deque<String> tail) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (tail) {
                    if (tail.size() >= 80) tail.removeFirst();
                    tail.addLast(line);
                }
            }
        } catch (IOException ignored) { }
    }

    private void register(Distribution distribution) {
        distributions.put(distribution.type, distribution);
    }

    private Distribution requireDistribution(AgentProviderType type) {
        Distribution distribution = distributions.get(type);
        if (distribution == null) {
            throw new IllegalArgumentException("provider is not npm-managed: " + type);
        }
        return distribution;
    }

    private static void requireExactVersion(String version) {
        if (!isExactVersion(version)) {
            throw new IllegalArgumentException("exact provider version is required: " + version);
        }
    }

    private static boolean isExactVersion(String version) {
        return version != null && EXACT_VERSION.matcher(version.trim()).matches();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static boolean followsAutoLatest(AcpRobotParam robot) {
        return robot != null && robot.isEnabled()
                && trimToNull(robot.getProviderVersion()) == null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    static List<String> npmInstallArguments(Path prefix, String registry, String packageSpec) {
        return Arrays.asList("install", "--global", "--prefix", prefix.toString(),
                "--registry", registry, "--include=optional", packageSpec);
    }

    private static String platformCommand(String command) {
        return isWindows() ? command + ".cmd" : command;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static final Comparator<String> VERSION_DESCENDING = (left, right) ->
            compareVersions(right, left);

    private static int compareVersions(String left, String right) {
        String[] leftParts = left.split("[-+]", 2)[0].split("\\.");
        String[] rightParts = right.split("[-+]", 2)[0].split("\\.");
        for (int i = 0; i < 3; i++) {
            int comparison = Integer.compare(Integer.parseInt(leftParts[i]),
                    Integer.parseInt(rightParts[i]));
            if (comparison != 0) return comparison;
        }
        boolean leftPre = left.contains("-");
        boolean rightPre = right.contains("-");
        if (leftPre != rightPre) return leftPre ? -1 : 1;
        return left.compareTo(right);
    }

    private static final class Distribution {
        private final AgentProviderType type;
        private final String runtimeKey;
        private final String packageName;
        private final String binName;

        private Distribution(AgentProviderType type, String runtimeKey,
                             String packageName, String binName) {
            this.type = type;
            this.runtimeKey = runtimeKey;
            this.packageName = packageName;
            this.binName = binName;
        }
    }

    static final class RuntimeInstall {
        final Path home;
        final Path executable;
        final String version;

        RuntimeInstall(Path home, Path executable, String version) {
            this.home = home;
            this.executable = executable;
            this.version = version;
        }
    }

    public static final class ReleaseCatalog {
        private final AgentProviderType provider;
        private final String packageName;
        private final Map<String, String> distTags;
        private final List<String> versions;
        private final List<String> installedVersions;

        ReleaseCatalog(AgentProviderType provider, String packageName,
                       Map<String, String> distTags, List<String> versions,
                       List<String> installedVersions) {
            this.provider = provider;
            this.packageName = packageName;
            this.distTags = distTags;
            this.versions = versions;
            this.installedVersions = installedVersions;
        }

        public AgentProviderType getProvider() { return provider; }
        public String getPackageName() { return packageName; }
        public Map<String, String> getDistTags() { return distTags; }
        public List<String> getVersions() { return versions; }
        public List<String> getInstalledVersions() { return installedVersions; }
    }

    public static final class RuntimeStatus {
        private final AgentProviderType provider;
        private final String packageName;
        private final List<String> installedVersions;
        private final String defaultVersion;

        RuntimeStatus(AgentProviderType provider, String packageName,
                      List<String> installedVersions, String defaultVersion) {
            this.provider = provider;
            this.packageName = packageName;
            this.installedVersions = installedVersions;
            this.defaultVersion = defaultVersion;
        }

        public AgentProviderType getProvider() { return provider; }
        public String getPackageName() { return packageName; }
        public List<String> getInstalledVersions() { return installedVersions; }
        public String getDefaultVersion() { return defaultVersion; }
    }

    public static final class InstallJob {
        private final String jobId;
        private final AgentProviderType provider;
        private final String version;
        private volatile String status = "QUEUED";
        private volatile int progress;
        private volatile String message = "等待安装";

        InstallJob(String jobId, AgentProviderType provider, String version) {
            this.jobId = jobId;
            this.provider = provider;
            this.version = version;
        }

        synchronized void update(String status, int progress, String message) {
            this.status = status;
            this.progress = progress;
            this.message = message;
        }

        synchronized void fail(String message) {
            update("FAILED", 100, message == null ? "安装失败" : message);
        }

        synchronized InstallJob snapshot() {
            InstallJob copy = new InstallJob(jobId, provider, version);
            copy.status = status;
            copy.progress = progress;
            copy.message = message;
            return copy;
        }

        public String getJobId() { return jobId; }
        public AgentProviderType getProvider() { return provider; }
        public String getVersion() { return version; }
        public String getStatus() { return status; }
        public int getProgress() { return progress; }
        public String getMessage() { return message; }
    }
}
