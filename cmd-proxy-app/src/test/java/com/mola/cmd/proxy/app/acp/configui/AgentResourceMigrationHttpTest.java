package com.mola.cmd.proxy.app.acp.configui;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.ZipInputStream;
import static org.junit.Assert.*;

public class AgentResourceMigrationHttpTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout=30000) public void realHttpZipDownloadAndSelectiveUpload() throws Exception {
        Path home=temporary.newFolder("runtime").toPath();
        AcpRobotParam source=robot(home,"source"),target=robot(home,"target");
        Path index=new AgentResourceBrowser().roots(source,"memory").get(0).path.resolve("MEMORY_INDEX.json");
        Files.createDirectories(index.getParent());Files.write(index,"{\"memories\":[]}".getBytes(StandardCharsets.UTF_8));
        Path mcp=Paths.get(source.getWorkDir(),".cmd-proxy/mcp.json");Files.createDirectories(mcp.getParent());Files.write(mcp,"{}".getBytes(StandardCharsets.UTF_8));
        JSONObject config=new JSONObject();JSONArray robots=new JSONArray();robots.add(source);robots.add(target);config.put("robots",robots);
        Files.write(home.resolve("acpConfig.json"),JSON.toJSONBytes(config));
        ProcessBuilder builder=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ServerProcess.class.getName());
        builder.environment().put("CMD_PROXY_HOME",home.toString());builder.redirectErrorStream(true);
        Process process=builder.start();
        try {
            BufferedReader output=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
            String line;int port=0;StringBuilder startup=new StringBuilder();
            while((line=output.readLine())!=null){startup.append(line).append("\n");if(line.startsWith("PORT=")){port=Integer.parseInt(line.substring(5));break;}}
            assertTrue("isolated ConfigUI must start: "+startup,port>0);
            HttpURLConnection download=request(port,"export?robot=source","GET",null);
            assertEquals(200,download.getResponseCode());assertEquals("application/zip",download.getContentType());
            byte[] zip=AgentResourceMigration.readBounded(download.getInputStream(),AgentResourceMigration.MAX_BYTES);download.disconnect();
            try(ZipInputStream input=new ZipInputStream(new ByteArrayInputStream(zip))){assertEquals("manifest.json",input.getNextEntry().getName());}
            HttpURLConnection empty=request(port,"import?robot=target&overwrite=true","POST",zip);
            assertEquals(400,empty.getResponseCode());empty.disconnect();
            HttpURLConnection unconfirmed=request(port,"import?robot=target&kinds=memory","POST",zip);
            assertEquals(400,unconfirmed.getResponseCode());unconfirmed.disconnect();
            HttpURLConnection upload=request(port,"import?robot=target&overwrite=true&kinds=memory","POST",zip);
            assertEquals(200,upload.getResponseCode());JSONObject result=JSON.parseObject(new String(AgentResourceMigration.readBounded(upload.getInputStream(),100000),StandardCharsets.UTF_8));
            assertTrue(result.getBooleanValue("ok"));assertEquals(1,result.getJSONObject("counts").getIntValue("memory"));upload.disconnect();
            assertTrue(Files.exists(new AgentResourceBrowser().roots(target,"memory").get(0).path.resolve("MEMORY_INDEX.json")));
            assertFalse(Files.exists(Paths.get(target.getWorkDir(),".cmd-proxy/mcp.json")));
        } finally { process.destroy();if(!process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS))process.destroyForcibly(); }
    }
    private static HttpURLConnection request(int port,String path,String method,byte[] body) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/api/agent-resources/"+path).openConnection();
        connection.setConnectTimeout(5000);connection.setReadTimeout(10000);connection.setRequestMethod(method);
        if(body!=null){connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/zip");try(OutputStream output=connection.getOutputStream()){output.write(body);}}
        return connection;
    }
    private static AcpRobotParam robot(Path home,String name) throws IOException {
        AcpRobotParam robot=new AcpRobotParam();robot.setName(name);robot.setAgentProvider("CODEX_ACP");robot.setWorkDir(Files.createDirectory(home.resolve(name)).toString());MemoryConfig memory=new MemoryConfig();memory.setBaseDir(home.resolve("memory").toString());robot.setMemory(memory);return robot;
    }
    public static class ServerProcess {
        public static void main(String[] args) throws Exception {
            ConfigUiServer server=new ConfigUiServer(0,()->{},name->{});server.start();System.out.println("PORT="+server.getBoundPort());System.out.flush();
            try { System.in.read(); } finally { server.stop(); }
        }
    }
}
