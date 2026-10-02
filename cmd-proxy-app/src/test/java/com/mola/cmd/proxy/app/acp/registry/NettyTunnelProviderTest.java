package com.mola.cmd.proxy.app.acp.registry;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.registry.tunnel.*;
import org.junit.Test;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import static org.junit.Assert.*;

public class NettyTunnelProviderTest {
    private static final TunnelProvider.Authorizer AUTH = new TunnelProvider.Authorizer() {
        public boolean authorize(String id, String lease, int port, String run) { return "environment".equals(id) && "lease".equals(lease) && port == 29999; }
        public void connected(String id, String lease, String run) { }
        public void disconnected(String id, String lease, String run) { }
    };
    @Test(timeout = 15000) public void rejectsInvalidTokensLeasesPortsVersionsAndUnsolicitedData() throws Exception {
        NettyTunnelProvider server = new NettyTunnelProvider(); int port = freePort();
        try {
            server.startServer(port, "token", AUTH);
            for (String invalid : new String[]{"token", "lease", "remotePort", "version", "DATA"}) {
                try (SSLSocket socket = socket(server.serverCertificate(), port)) {
                    JSONObject m = hello();
                    if ("DATA".equals(invalid)) { m.put("type", "DATA"); m.put("stream", "unsolicited"); }
                    else m.put(invalid, "remotePort".equals(invalid) ? 22 : "version".equals(invalid) ? 2 : "wrong");
                    send(socket, m);
                    assertEquals("invalid " + invalid + " must close without admitting a tunnel", -1, socket.getInputStream().read());
                }
            }
            assertTrue(server.serverAlive());
        } finally { server.close(); server.close(); }
        try (ServerSocket socket = new ServerSocket(port)) { assertTrue(socket.isBound()); }
    }
    @Test(timeout = 15000) public void rejectsUntrustedServerCertificateAndOversizedFrames() throws Exception {
        NettyTunnelProvider server = new NettyTunnelProvider(), other = new NettyTunnelProvider(); int port = freePort();
        try {
            server.startServer(port, "token", AUTH); other.startServer(freePort(), "other", AUTH);
            try (SSLSocket socket = socket(other.serverCertificate(), port)) {
                try { socket.startHandshake(); fail("wrong certificate trusted"); } catch (SSLException expected) { }
            }
            try (SSLSocket socket = socket(server.serverCertificate(), port)) {
                socket.startHandshake(); new DataOutputStream(socket.getOutputStream()).writeInt(1024 * 1024);
                assertEquals(-1, socket.getInputStream().read());
            }
        } finally { server.close(); other.close(); }
    }
    private static JSONObject hello() {
        JSONObject m = new JSONObject(); m.put("version", 1); m.put("type", "CONTROL");
        m.put("token", "token"); m.put("environmentId", "environment"); m.put("lease", "lease"); m.put("remotePort", 29999); return m;
    }
    private static void send(SSLSocket socket, JSONObject m) throws IOException {
        byte[] bytes = m.toJSONString().getBytes(StandardCharsets.UTF_8);
        DataOutputStream output = new DataOutputStream(socket.getOutputStream()); output.writeInt(bytes.length); output.write(bytes); output.flush();
    }
    private static SSLSocket socket(String certificate, int port) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null, null);
        trust.setCertificateEntry("center", CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(certificate))));
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLSv1.2"); context.init(null, factory.getTrustManagers(), null);
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", port); socket.setSoTimeout(3000); return socket;
    }
    private static int freePort() throws IOException { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
}
