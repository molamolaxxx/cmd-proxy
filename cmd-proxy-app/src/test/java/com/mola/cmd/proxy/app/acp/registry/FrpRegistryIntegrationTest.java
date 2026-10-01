package com.mola.cmd.proxy.app.acp.registry;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.configui.EnvironmentHttpProxy;
import com.sun.net.httpserver.HttpServer;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** 显式启用官方 frp 下载和真实隧道，常规回归不依赖外网。 */
public class FrpRegistryIntegrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private HttpServer centerHttp, localHttp;
    private RegistryManager center, local;
    private EnvironmentHttpProxy proxy;
    private ExecutorService httpThreads;
    private final byte[] binary={0,1,(byte)255,(byte)128,13,10};
    @Test(timeout=180_000) public void realTunnelRegistersProxiesAndRecoversAfterCenterRestart() throws Exception {
        Assume.assumeTrue("enable with -Dfrp.integration=true", Boolean.getBoolean("frp.integration"));
        Path centerDirectory=temporary.newFolder("center").toPath(),localDirectory=temporary.newFolder("local").toPath();
        String credential="integration-credential-012345678901234567890";
        int tunnelPort;try(ServerSocket socket=new ServerSocket(0)){tunnelPort=socket.getLocalPort();}
        centerHttp=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        localHttp=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        httpThreads=Executors.newCachedThreadPool();centerHttp.setExecutor(httpThreads);localHttp.setExecutor(httpThreads);
        proxy=new EnvironmentHttpProxy();
        center=new RegistryManager(centerDirectory,centerHttp.getAddress().getPort(),"center");
        local=new RegistryManager(localDirectory,localHttp.getAddress().getPort(),"local");
        centerHttp.createContext("/api/registry/",exchange->center.handleControl(exchange));
        localHttp.createContext("/api/registry/",exchange->local.handleControl(exchange));
        localHttp.createContext("/binary",exchange->{exchange.getResponseHeaders().set("Content-Type","application/octet-stream");exchange.getResponseHeaders().set("Content-Disposition","attachment; filename=sample.bin");exchange.sendResponseHeaders(200,binary.length);try(OutputStream output=exchange.getResponseBody()){output.write(binary);}});
        centerHttp.createContext("/binary",exchange->{String id=exchange.getRequestURI().getRawQuery().substring("instance=".length());proxy.forward(exchange,center.resolve(id));});
        centerHttp.start();localHttp.start();center.start(500);local.start(500);
        try {
            JSONObject settings=new JSONObject();settings.put("serverEnabled",true);settings.put("tunnelPort",tunnelPort);settings.put("serverCredential",credential);center.configure(settings);
            await(()->"RUNNING".equals(center.settings().getString("serverStatus")),60_000,()->center.settings().toJSONString());
            RegistryClient client=new RegistryClient();JSONObject request=new JSONObject();request.put("nodeId","invalid");request.put("instanceId","x");request.put("displayName","x");
            try{client.request(baseUrl(),"register","wrong",request);fail("bad credential accepted");}catch(IOException expected){}
            settings=new JSONObject();settings.put("clientEnabled",true);settings.put("centerUrl",baseUrl());settings.put("clientCredential",credential);settings.put("displayName","我的电脑");local.configure(settings);
            await(()->"REGISTERED".equals(local.settings().getString("clientStatus")),60_000,()->local.settings().toJSONString());
            assertEquals(1,center.environments().size());String id=center.environments().get(0).instanceId;assertTrue(center.environments().get(0).online);
            checkDownload(id);
            assertEquals(RegistryManager.MASK,local.settings().getString("clientCredential"));assertFalse(local.settings().toJSONString().contains(credential));
            center.close();
            center=new RegistryManager(centerDirectory,centerHttp.getAddress().getPort(),"center");center.start(500);
            await(()->"REGISTERED".equals(local.settings().getString("clientStatus"))&&center.environments().get(0).online,60_000,()->local.settings().toJSONString()+center.settings().toJSONString());
            assertEquals(id,center.environments().get(0).instanceId);checkDownload(id);
            settings=new JSONObject();settings.put("clientEnabled",false);local.configure(settings);
            await(()->!center.environments().get(0).online,5000,()->"still online");
            try{center.resolve(id);fail("offline environment resolved");}catch(IllegalStateException expected){}
        } catch (Throwable failure) {
            System.err.println("Identity diagnostic: " + identityDiagnostic());
            for (Path directory : new Path[]{centerDirectory, localDirectory}) {
                for (String name : new String[]{"frps.log", "frpc.log"}) {
                    Path log = directory.resolve(name);
                    if (java.nio.file.Files.exists(log)) {
                        String text = new String(java.nio.file.Files.readAllBytes(log), StandardCharsets.UTF_8);
                        System.err.println(name + ": " + text.substring(Math.max(0, text.length() - 6000)).replace(credential, "[redacted]"));
                    }
                }
            }
            throw failure;
        } finally {if(local!=null)local.close();if(center!=null)center.close();proxy.close();centerHttp.stop(0);localHttp.stop(0);httpThreads.shutdownNow();}
    }
    private String identityDiagnostic() {
        try {
            java.lang.reflect.Field field = RegistryManager.class.getDeclaredField("connection"); field.setAccessible(true);
            JSONObject connection = (JSONObject) field.get(local);
            if (connection == null) return "no client connection";
            StringBuilder result = new StringBuilder();
            int[] ports = {localHttp.getAddress().getPort(), center.environments().get(0).configUiPort};
            for (int port : ports) {
                HttpURLConnection request = (HttpURLConnection)new URL("http://127.0.0.1:"+port+"/api/registry/identity").openConnection();
                request.setConnectTimeout(2000);request.setReadTimeout(2000);
                request.setRequestProperty("X-Starweave-Registry-Lease", connection.getString("lease"));
                try { result.append(port).append(" status ").append(request.getResponseCode());
                    if(request.getResponseCode()==200) result.append(" body ").append(RegistryClient.read(request.getInputStream()));
                }catch(Exception e){result.append(" error ").append(e.getClass().getSimpleName());}finally{request.disconnect();}
            }
            java.lang.reflect.Field environmentField = RegistryManager.class.getDeclaredField("environments"); environmentField.setAccessible(true);
            RemoteEnvironmentRegistry registry = (RemoteEnvironmentRegistry)environmentField.get(center);
            java.lang.reflect.Method verify = RegistryManager.class.getDeclaredMethod("verify", RemoteEnvironmentRegistry.Entry.class); verify.setAccessible(true);
            for (RemoteEnvironmentRegistry.Entry entry : registry.candidates()) {
                result.append(" entry ").append(entry.sourceInstanceId).append(" node ").append(entry.nodeId)
                    .append(" verified ").append(entry.lastVerified).append(" heartbeat ").append(entry.lastHeartbeat)
                    .append(" online ").append(entry.online).append(" probeNow ").append(verify.invoke(center,entry));
            }
            result.append(" center ").append(center.settings().getString("serverStatus"));
            return result.toString();
        }catch(Exception e){return e.getClass().getSimpleName();}
    }
    private String baseUrl(){return "http://127.0.0.1:"+centerHttp.getAddress().getPort();}
    private void checkDownload(String id)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(baseUrl()+"/binary?instance="+id).openConnection();connection.setReadTimeout(5000);
        assertEquals(200,connection.getResponseCode());assertEquals("attachment; filename=sample.bin",connection.getHeaderField("Content-Disposition"));
        try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] b=new byte[1024];int n;while((n=input.read(b))>=0)output.write(b,0,n);assertArrayEquals(binary,output.toByteArray());}connection.disconnect();
    }
    private static void await(BooleanSupplier condition,long timeout,java.util.function.Supplier<String> diagnostic)throws Exception {
        long deadline=System.currentTimeMillis()+timeout;
        while(!condition.getAsBoolean()&&System.currentTimeMillis()<deadline)Thread.sleep(100);
        assertTrue(diagnostic.get(),condition.getAsBoolean());
    }
}
