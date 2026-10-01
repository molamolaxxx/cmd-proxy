package com.mola.cmd.proxy.app.acp.registry.tunnel;

import java.io.IOException;

/** 隧道仅映射当前 ConfigUI；不参与 Agent、会话或调度。 */
public interface TunnelProvider extends AutoCloseable {
    void startServer(int tunnelPort, String token, String pluginAddress, String pluginPath) throws IOException;
    void startClient(String host, int tunnelPort, String token, String environmentId,
                     String lease, int remotePort, int localPort) throws IOException;
    boolean serverAlive();
    boolean clientAlive();
    void stopServer();
    void stopClient();
    void close();
}
