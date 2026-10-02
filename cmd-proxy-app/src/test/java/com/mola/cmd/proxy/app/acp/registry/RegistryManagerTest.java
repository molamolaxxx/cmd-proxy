package com.mola.cmd.proxy.app.acp.registry;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.registry.tunnel.TunnelProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RegistryManagerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void registrationWithoutCredentialsRequiresEnabledReadyTunnel() throws Exception {
        Path directory=temporary.newFolder().toPath();
        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        FakeTunnel tunnel=new FakeTunnel();RegistryManager manager=new RegistryManager(directory,http.getAddress().getPort(),"local",tunnel);
        http.createContext("/api/registry/",manager::handleControl);http.createContext("/api/registry/settings",manager::handleAdmin);
        http.start();manager.start(500);String url="http://127.0.0.1:"+http.getAddress().getPort();
        try {
            assertFalse(manager.settings().getBooleanValue("serverEnabled"));assertEquals(0,tunnel.starts.get());
            RegistryClient client=new RegistryClient();JSONObject input=new JSONObject();input.put("nodeId","node");input.put("instanceId","remote-source");input.put("displayName","电脑");
            try{client.request(url,"register",input);fail();}catch(IOException expected){}
            int port;try(ServerSocket socket=new ServerSocket(0)){port=socket.getLocalPort();}
            JSONObject settings=new JSONObject();settings.put("serverEnabled",true);settings.put("tunnelPort",port);
            JSONObject result=client.request(url,"settings",settings);assertFalse(result.containsKey("serverCredential"));assertFalse(result.containsKey("clientCredential"));
            assertTrue(tunnel.started.await(3,TimeUnit.SECONDS));
            long deadline=System.currentTimeMillis()+3000;while(!"RUNNING".equals(manager.settings().getString("serverStatus"))&&System.currentTimeMillis()<deadline)Thread.sleep(20);
            assertEquals("RUNNING",manager.settings().getString("serverStatus"));
            try{client.request(url,"settings/credential",new JSONObject());fail("removed credential endpoint accepted");}catch(IOException expected){}
            JSONObject registration=client.request(url,"register",input);assertFalse(registration.getBooleanValue("online"));assertEquals(1,manager.environments().size());
            try{manager.resolve(registration.getString("environmentId"));fail();}catch(IllegalStateException expected){}
            JSONObject meta=new JSONObject();meta.put("environmentId",registration.getString("environmentId"));meta.put("lease",registration.getString("lease"));
            assertTrue(tunnel.authorizer.authorize(registration.getString("environmentId"), registration.getString("lease"), registration.getIntValue("remotePort"), null));
            meta.put("lease","wrong");assertFalse(tunnel.authorizer.authorize(registration.getString("environmentId"), "wrong", registration.getIntValue("remotePort"), null));
            try{client.request(url,"heartbeat",meta);fail("invalid lease accepted");}catch(IOException expected){}
            try{client.request(url,"unregister",meta);fail("invalid lease accepted");}catch(IOException expected){}
            assertEquals(1,manager.environments().size());
            manager.close();manager.close();assertEquals(1,tunnel.closes.get());
            RegistryManager recovered=new RegistryManager(directory,http.getAddress().getPort(),"local",new FakeTunnel());
            try{assertEquals(1,recovered.environments().size());assertFalse(recovered.environments().get(0).online);}finally{recovered.close();}
        } finally {manager.close();http.stop(0);}
    }
    @Test public void invalidSettingsDoNotOverwriteSavedConfiguration() throws Exception {
        RegistryManager manager=new RegistryManager(temporary.newFolder().toPath(),12345,"local",new FakeTunnel());manager.start();
        try {
            JSONObject input=new JSONObject();input.put("clientEnabled",true);input.put("centerUrl","file:///tmp/a");
            try{manager.configure(input);fail();}catch(IllegalArgumentException expected){}
            assertFalse(manager.settings().getBooleanValue("clientEnabled"));
        }finally{manager.close();}
    }
    @Test public void nameDefaultsToSourceInstanceAndCustomNameSurvivesRestart() throws Exception {
        Path directory = temporary.newFolder().toPath();
        RegistryManager manager = new RegistryManager(directory, 12345, "environment-b", new FakeTunnel());
        manager.start();
        try {
            assertEquals("environment-b", manager.settings().getString("displayName"));
            JSONObject input = new JSONObject(); input.put("displayName", "我的开发电脑");
            assertEquals("我的开发电脑", manager.configure(input).getString("displayName"));
        } finally { manager.close(); }
        RegistryManager restored = new RegistryManager(directory, 12345, "environment-b", new FakeTunnel());
        restored.start();
        try {
            assertEquals("我的开发电脑", restored.settings().getString("displayName"));
            JSONObject input = new JSONObject(); input.put("displayName", "   ");
            assertEquals("environment-b", restored.configure(input).getString("displayName"));
        } finally { restored.close(); }
    }
    private static final class FakeTunnel implements TunnelProvider {
        private ServerSocket socket; Authorizer authorizer;
        final AtomicInteger starts=new AtomicInteger(),closes=new AtomicInteger();final CountDownLatch started=new CountDownLatch(1);
        @Override public synchronized void startServer(int port,String token,Authorizer authorizer)throws IOException{socket=new ServerSocket(port,100,InetAddress.getLoopbackAddress());this.authorizer=authorizer;starts.incrementAndGet();started.countDown();}
        @Override public String serverCertificate(){return "test-certificate";}
        @Override public void startClient(String host,int port,String token,String id,String lease,int remotePort,int localPort,String certificate){}
        @Override public synchronized boolean serverAlive(){return socket!=null&&!socket.isClosed();}
        @Override public boolean clientAlive(){return false;}
        @Override public synchronized void stopServer(){if(socket!=null)try{socket.close();}catch(IOException ignored){}socket=null;}
        @Override public void stopClient(){}
        @Override public void close(){closes.incrementAndGet();stopServer();}
    }
}
