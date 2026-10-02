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

/** 真实 TLS 反向隧道纳入常规回归，不依赖外网或外部进程。 */
public class NettyRegistryIntegrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private HttpServer centerHttp, localHttp;
    private RegistryManager center, local;
    private EnvironmentHttpProxy proxy;
    private ExecutorService httpThreads;
    private final byte[] binary={0,1,(byte)255,(byte)128,13,10};
    private final CountDownLatch endStream = new CountDownLatch(1);
    @Test(timeout=180_000) public void realTunnelRegistersProxiesAndRecoversAfterCenterRestart() throws Exception {
        Path centerDirectory=temporary.newFolder("center").toPath(),localDirectory=temporary.newFolder("local").toPath();
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
        localHttp.createContext("/upload", exchange -> {
            try (InputStream input = exchange.getRequestBody(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) >= 0) body.write(buffer, 0, n);
                byte[] bytes = body.toByteArray(); exchange.sendResponseHeaders(201, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
            }
        });
        localHttp.createContext("/events", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write("data: first\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
                try { endStream.await(15, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                output.write("data: last\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
            }
        });
        centerHttp.createContext("/binary",exchange->{String id=exchange.getRequestURI().getRawQuery().substring("instance=".length());proxy.forward(exchange,center.resolve(id));});
        for (String path : new String[]{"/upload", "/events"}) centerHttp.createContext(path, exchange -> {
            String id = exchange.getRequestURI().getRawQuery().substring("instance=".length()); proxy.forward(exchange, center.resolve(id));
        });
        centerHttp.start();localHttp.start();center.start(500);local.start(500);
        try {
            JSONObject settings=new JSONObject();settings.put("serverEnabled",true);settings.put("tunnelPort",tunnelPort);center.configure(settings);
            await(()->"RUNNING".equals(center.settings().getString("serverStatus")),60_000,()->center.settings().toJSONString());
            settings=new JSONObject();settings.put("clientEnabled",true);settings.put("centerUrl",baseUrl());settings.put("displayName","我的电脑");local.configure(settings);
            await(()->"REGISTERED".equals(local.settings().getString("clientStatus")),60_000,()->local.settings().toJSONString());
            assertEquals(1,center.environments().size());String id=center.environments().get(0).instanceId;assertTrue(center.environments().get(0).online);
            checkDownload(id);
            checkUpload(id);
            checkStreamWithConcurrentDownload(id);
            assertFalse(local.settings().containsKey("clientCredential"));assertFalse(center.settings().containsKey("serverCredential"));
            center.close();
            center=new RegistryManager(centerDirectory,centerHttp.getAddress().getPort(),"center");center.start(500);
            await(()->"REGISTERED".equals(local.settings().getString("clientStatus"))&&center.environments().get(0).online,60_000,()->local.settings().toJSONString()+center.settings().toJSONString());
            assertEquals(id,center.environments().get(0).instanceId);checkDownload(id);
            settings=new JSONObject();settings.put("clientEnabled",false);local.configure(settings);
            await(()->!center.environments().get(0).online,5000,()->"still online");
            try{center.resolve(id);fail("offline environment resolved");}catch(IllegalStateException expected){}
        } catch (Throwable failure) {
            System.err.println("Identity diagnostic: " + identityDiagnostic());
            throw failure;
        } finally {endStream.countDown();if(local!=null)local.close();if(center!=null)center.close();proxy.close();centerHttp.stop(0);localHttp.stop(0);httpThreads.shutdownNow();}
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
    private void checkUpload(String id) throws Exception {
        byte[] payload = new byte[2 * 1024 * 1024]; new java.util.Random(1).nextBytes(payload);
        HttpURLConnection request = (HttpURLConnection) new URL(baseUrl() + "/upload?instance=" + id).openConnection();
        request.setRequestMethod("POST"); request.setDoOutput(true); request.setFixedLengthStreamingMode(payload.length); request.setReadTimeout(10000);
        try {
            try (OutputStream output = request.getOutputStream()) { output.write(payload); }
            assertEquals(201, request.getResponseCode());
            try (InputStream input = request.getInputStream(); ByteArrayOutputStream received = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) >= 0) received.write(buffer, 0, n);
                assertArrayEquals(payload, received.toByteArray());
            }
        } finally { request.disconnect(); }
    }
    private void checkStreamWithConcurrentDownload(String id) throws Exception {
        HttpURLConnection request = (HttpURLConnection) new URL(baseUrl() + "/events?instance=" + id).openConnection(); request.setReadTimeout(5000);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("data: first", reader.readLine()); assertEquals("", reader.readLine());
            checkDownload(id); // SSE 尚未结束时其它请求应正常完成。
            endStream.countDown(); assertEquals("data: last", reader.readLine()); assertEquals("", reader.readLine()); assertNull(reader.readLine());
        } finally { request.disconnect(); }
    }
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
