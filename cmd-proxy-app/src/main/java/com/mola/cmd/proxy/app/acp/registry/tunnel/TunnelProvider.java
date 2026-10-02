package com.mola.cmd.proxy.app.acp.registry.tunnel;

import java.io.IOException;

/** 隧道仅映射当前 ConfigUI；不参与 Agent、会话或调度。 */
public interface TunnelProvider extends AutoCloseable {
    interface Authorizer {
        boolean authorize(String environmentId, String lease, int port, String connectionId);
        void connected(String environmentId, String lease, String connectionId);
        void disconnected(String environmentId, String lease, String connectionId);
    }
    void startServer(int tunnelPort, String token, Authorizer authorizer) throws IOException;
    String serverCertificate();
    void startClient(String host, int tunnelPort, String token, String environmentId,
                     String lease, int remotePort, int localPort, String certificate) throws IOException;
    boolean serverAlive();
    boolean clientAlive();
    void stopServer();
    void stopClient();
    void close();
}
