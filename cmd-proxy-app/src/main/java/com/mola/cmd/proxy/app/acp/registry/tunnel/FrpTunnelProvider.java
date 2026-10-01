package com.mola.cmd.proxy.app.acp.registry.tunnel;

import com.alibaba.fastjson.JSON;
import com.mola.cmd.proxy.app.acp.registry.RegistryConfigStore;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

public final class FrpTunnelProvider implements TunnelProvider {
    private final RegistryConfigStore store;
    private final FrpRuntimeInstaller installer;
    private Process server;
    private Process client;
    private boolean closed;
    public FrpTunnelProvider(Path directory) {
        store = new RegistryConfigStore(directory);
        installer = new FrpRuntimeInstaller(directory.resolve("runtime"));
    }
    @Override public void startServer(int port, String token, String pluginAddress, String pluginPath) throws IOException {
        Path binary = installer.executable("frps");
        synchronized (this) {
            if (closed) throw new IOException("隧道已关闭");
            stopServer();
            String config = "bindAddr = \"0.0.0.0\"\nbindPort = " + port
                    + "\nproxyBindAddr = \"127.0.0.1\"\ntransport.tls.force = true\nauth.token = " + JSON.toJSONString(token)
                    + "\nmaxPortsPerClient = 1\nallowPorts = [{start = 20000, end = 29999}]\nlog.level = \"warn\"\n"
                    + "\n[[httpPlugins]]\nname = \"starweave-registry\"\naddr = " + JSON.toJSONString(pluginAddress)
                    + "\npath = " + JSON.toJSONString(pluginPath) + "\nops = [\"Login\", \"NewProxy\", \"CloseProxy\", \"Ping\"]\n";
            store.write("frps.toml", config);
            server = launch(binary, "frps.toml", "frps.log");
        }
    }
    @Override public void startClient(String host, int port, String token, String id, String lease, int remotePort, int localPort) throws IOException {
        Path binary = installer.executable("frpc");
        synchronized (this) {
            if (closed) throw new IOException("隧道已关闭");
            stopClient();
            String config = "serverAddr = " + JSON.toJSONString(host) + "\nserverPort = " + port
                    + "\nauth.token = " + JSON.toJSONString(token)
                    + "\ntransport.tls.enable = true\nloginFailExit = false\nlog.level = \"warn\"\n"
                    + "metadatas.environmentId = " + JSON.toJSONString(id)
                    + "\nmetadatas.lease = " + JSON.toJSONString(lease)
                    + "\n\n[[proxies]]\nname = " + JSON.toJSONString(id) + "\ntype = \"tcp\"\nlocalIP = \"127.0.0.1\"\nlocalPort = "
                    + localPort + "\nremotePort = " + remotePort + "\n";
            store.write("frpc.toml", config);
            client = launch(binary, "frpc.toml", "frpc.log");
        }
    }
    private Process launch(Path binary, String config, String log) throws IOException {
        Path logPath = store.directory().resolve(log);
        // 仅保留本次启动日志，避免长期运行无限增长；不向 UI 返回子进程原始日志。
        if (!Files.exists(logPath)) Files.createFile(logPath);
        RegistryConfigStore.protect(logPath);
        return new ProcessBuilder(binary.toString(), "-c", store.directory().resolve(config).toAbsolutePath().toString())
                .redirectErrorStream(true).redirectOutput(logPath.toFile()).start();
    }
    @Override public synchronized boolean serverAlive() { return server != null && server.isAlive(); }
    @Override public synchronized boolean clientAlive() { return client != null && client.isAlive(); }
    @Override public synchronized void stopServer() { terminate(server); server = null; }
    @Override public synchronized void stopClient() { terminate(client); client = null; }
    private static void terminate(Process process) {
        if (process == null) return;
        process.destroy();
        try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
        catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
    }
    @Override public synchronized void close() { closed = true; stopClient(); stopServer(); }
}
