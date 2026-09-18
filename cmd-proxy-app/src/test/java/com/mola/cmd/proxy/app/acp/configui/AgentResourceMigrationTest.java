package com.mola.cmd.proxy.app.acp.configui;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class AgentResourceMigrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final AgentResourceMigration migration = new AgentResourceMigration();

    @Test public void roundTripMasksHomeAndRebindsWorkspaceAndMemory() throws Exception {
        AcpRobotParam source = robot("source"), target = robot("target");
        Path sourceMemory = memory(source), targetMemory = memory(target);
        write(Paths.get(source.getWorkDir(), ".cmd-proxy/mcp.json"), "{\"cwd\":\""+source.getWorkDir()+"\",\"home\":\""+System.getProperty("user.home")+"/bin\"}");
        write(Paths.get(source.getWorkDir(), ".agents/skills/demo/SKILL.md"), "# Skill\n"+source.getWorkDir());
        write(sourceMemory.resolve("MEMORY_INDEX.json"), "{\"memories\":[{\"id\":\"m1\",\"file\":\""+sourceMemory+"/memories/a.md\"}]}");
        write(sourceMemory.resolve("memories/a.md"), System.getProperty("user.home")+"/test "+sourceMemory+"/memories/a.md");
        write(sourceMemory.resolve("archive/hidden.md"), "excluded");
        write(Paths.get(source.getWorkDir(), ".claude/skills/foreign/SKILL.md"), "excluded");
        Map<String, byte[]> archive = unzip(migration.exportZip(source));
        assertEquals(5, archive.size());
        for (byte[] bytes : archive.values()) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            assertFalse(text.contains(System.getProperty("user.home")));
            assertFalse(text.contains(source.getWorkDir()));
        }
        JSONObject result = migration.importZip(target, new ByteArrayInputStream(zip(archive)), kinds("mcp", "skill", "memory"));
        assertTrue(result.getBooleanValue("ok"));
        assertEquals(4, result.getJSONArray("succeeded").size());
        assertTrue(read(Paths.get(target.getWorkDir(), ".cmd-proxy/mcp.json")).contains(target.getWorkDir()));
        assertTrue(read(targetMemory.resolve("MEMORY_INDEX.json")).contains(targetMemory.toString()));
        assertTrue(read(targetMemory.resolve("memories/a.md")).contains(System.getProperty("user.home")+"/test"));
    }

    @Test public void memoryOnlyAllowsDifferentProviderAndLeavesOtherFilesUntouched() throws Exception {
        AcpRobotParam source=robot("source"), target=robot("target");
        target.setAgentProvider("CLAUDE_AGENT_ACP");
        write(memory(source).resolve("MEMORY_INDEX.json"), "{\"memories\":[]}");
        write(Paths.get(source.getWorkDir(), ".cmd-proxy/mcp.json"), "new");
        write(Paths.get(target.getWorkDir(), ".cmd-proxy/mcp.json"), "old");
        JSONObject result=migration.importZip(target, new ByteArrayInputStream(migration.exportZip(source)), kinds("memory"));
        assertTrue(result.getBooleanValue("ok"));
        assertEquals("old", read(Paths.get(target.getWorkDir(), ".cmd-proxy/mcp.json")));
        assertEquals(1,result.getJSONArray("succeeded").size());
    }

    @Test public void emptySelectionRejectedBeforeReading() throws Exception {
        try { migration.importZip(robot("target"), new ByteArrayInputStream(new byte[0]), kinds()); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("至少选择")); }
    }

    @Test public void traversalAndUnrecognizedResourcesRejectedBeforeAnyWrite() throws Exception {
        AcpRobotParam source=robot("source"), target=robot("target");
        for(String bad : Arrays.asList("skill/../outside", "mcp/.ssh/authorized_keys", "memory/archive/other.md", "/tmp/outside")) {
            Map<String,byte[]> entries=unzip(migration.exportZip(source));
            entries.put("mcp/.cmd-proxy/mcp.json", "new".getBytes(StandardCharsets.UTF_8));
            entries.put(bad, new byte[]{1});
            try { migration.importZip(target,new ByteArrayInputStream(zip(entries)),kinds("mcp","skill","memory"));fail(bad); }
            catch(IllegalArgumentException expected) { assertFalse(Files.exists(Paths.get(target.getWorkDir(),".cmd-proxy/mcp.json"))); }
        }
    }

    @Test public void missingOrOutsideMemoryDetailsRejectImportBeforeOverwritingAnyFile() throws Exception {
        AcpRobotParam source = robot("source"), target = robot("target");
        Path targetMcp = Paths.get(target.getWorkDir(), ".cmd-proxy/mcp.json");
        Path targetIndex = memory(target).resolve("MEMORY_INDEX.json");
        write(targetMcp, "old mcp");
        write(targetIndex, "old index");
        write(Paths.get(source.getWorkDir(), ".cmd-proxy/mcp.json"), "new mcp");
        for (String detail : Arrays.asList("__CMD_PROXY_MEMORY__/memories/missing.md",
                "__CMD_PROXY_MEMORY__/../outside.md")) {
            Map<String, byte[]> entries = unzip(migration.exportZip(source));
            JSONObject entry = new JSONObject();
            entry.put("file", detail);
            JSONObject index = new JSONObject();
            index.put("memories", Collections.singletonList(entry));
            entries.put("memory/MEMORY_INDEX.json", index.toJSONString().getBytes(StandardCharsets.UTF_8));
            try {
                migration.importZip(target, new ByteArrayInputStream(zip(entries)), kinds("mcp", "memory"));
                fail("Invalid memory reference must reject the entire import");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("MEMORY_INDEX.json"));
                assertEquals("old mcp", read(targetMcp));
                assertEquals("old index", read(targetIndex));
            }
        }
    }

    @Test public void reportsPerFileFailureAndPreservesIndexWhenDetailsFail() throws Exception {
        AcpRobotParam source=robot("source"), target=robot("target");
        write(memory(source).resolve("MEMORY_INDEX.json"), "{\"memories\":[]}");
        write(memory(source).resolve("memories/a.md"), "new");
        write(memory(target).resolve("MEMORY_INDEX.json"), "old index");
        Files.createDirectories(memory(target).resolve("memories/a.md/child"));
        JSONObject result=migration.importZip(target,new ByteArrayInputStream(migration.exportZip(source)),kinds("memory"));
        assertFalse(result.getBooleanValue("ok"));
        assertEquals(2,result.getJSONArray("failed").size());
        assertEquals("old index",read(memory(target).resolve("MEMORY_INDEX.json")));
    }

    @Test public void symlinkDestinationCannotOverwriteExternalFile() throws Exception {
        AcpRobotParam source=robot("source"),target=robot("target");
        write(Paths.get(source.getWorkDir(),".cmd-proxy/mcp.json"),"new");
        Path outside=temp.newFolder("outside").toPath();write(outside.resolve("mcp.json"),"safe");
        Files.createSymbolicLink(Paths.get(target.getWorkDir(),".cmd-proxy"),outside);
        JSONObject result=migration.importZip(target,new ByteArrayInputStream(migration.exportZip(source)),kinds("mcp"));
        assertFalse(result.getBooleanValue("ok"));assertEquals("safe",read(outside.resolve("mcp.json")));
    }

    @Test public void binarySkillAssetIsPreserved() throws Exception {
        AcpRobotParam source=robot("source"),target=robot("target");
        byte[] bytes={(byte)0xff,0,1,2};
        Path asset=Paths.get(source.getWorkDir(),".agents/skills/demo/asset.bin");Files.createDirectories(asset.getParent());Files.write(asset,bytes);
        migration.importZip(target,new ByteArrayInputStream(migration.exportZip(source)),kinds("skill"));
        assertArrayEquals(bytes,Files.readAllBytes(Paths.get(target.getWorkDir(),".agents/skills/demo/asset.bin")));
    }

    @Test public void providerMismatchRejectedForMcpAndSizeIsBounded() throws Exception {
        AcpRobotParam source=robot("source"),target=robot("target");target.setAgentProvider("KIRO_CLI");
        try { migration.importZip(target,new ByteArrayInputStream(migration.exportZip(source)),kinds("mcp"));fail(); }
        catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("相同类型")); }
        try { AgentResourceMigration.readBounded(new ByteArrayInputStream(new byte[5]),4);fail(); }
        catch(IOException expected) { assertTrue(expected.getMessage().contains("限制")); }
    }

    @Test public void homeChangesOnImportAndExecutableSkillsSurvive() throws Exception {
        AcpRobotParam source=robot("source"), target=robot("target");
        target.getMemory().setScope("robot");
        Path script=Paths.get(source.getWorkDir(),".agents/skills/demo/run.sh");
        write(script,"#!/bin/sh\necho "+System.getProperty("user.home")+"/bin");
        script.toFile().setExecutable(true, true);
        byte[] archive=migration.exportZip(source);
        String previous=System.getProperty("user.home");
        try {
            System.setProperty("user.home",temp.newFolder("new-home").getAbsolutePath());
            migration.importZip(target,new ByteArrayInputStream(archive),kinds("skill"));
            Path imported=Paths.get(target.getWorkDir(),".agents/skills/demo/run.sh");
            assertTrue(read(imported).contains(System.getProperty("user.home")+"/bin"));
            assertFalse(read(imported).contains(previous+"/bin"));assertTrue(Files.isExecutable(imported));
        } finally { System.setProperty("user.home",previous); }
    }

    @Test public void windowsJsonPathsAreMaskedAndPortable() throws Exception {
        AcpRobotParam source=robot("source"),target=robot("target");
        String previous=System.getProperty("user.home");byte[] archive;
        try {
            System.setProperty("user.home","C:\\Users\\alice");
            JSONObject config=new JSONObject();config.put("path",System.getProperty("user.home")+"\\bin");
            write(Paths.get(source.getWorkDir(),".cmd-proxy/mcp.json"),config.toJSONString());
            archive=migration.exportZip(source);
            assertFalse(new String(unzip(archive).get("mcp/.cmd-proxy/mcp.json"),StandardCharsets.UTF_8).contains("alice"));
        } finally { System.setProperty("user.home",previous); }
        migration.importZip(target,new ByteArrayInputStream(archive),kinds("mcp"));
        assertTrue(JSONObject.parseObject(read(Paths.get(target.getWorkDir(),".cmd-proxy/mcp.json"))).getString("path").startsWith(previous));
    }

    private AcpRobotParam robot(String name) throws IOException {
        AcpRobotParam robot=new AcpRobotParam();robot.setName(name);robot.setAgentProvider("CODEX_ACP");robot.setWorkDir(temp.newFolder(name).getAbsolutePath());
        MemoryConfig config=new MemoryConfig();config.setBaseDir(temp.newFolder(name+"-memory").getAbsolutePath());robot.setMemory(config);return robot;
    }
    private Path memory(AcpRobotParam robot) { return new AgentResourceBrowser().roots(robot,"memory").get(0).path; }
    private static Set<String> kinds(String... kinds) { return new LinkedHashSet<>(Arrays.asList(kinds)); }
    private static void write(Path path,String text) throws IOException { Files.createDirectories(path.getParent());Files.write(path,text.getBytes(StandardCharsets.UTF_8)); }
    private static String read(Path path) throws IOException { return new String(Files.readAllBytes(path),StandardCharsets.UTF_8); }
    private static Map<String,byte[]> unzip(byte[] bytes) throws IOException {
        Map<String,byte[]> result=new LinkedHashMap<>();try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)result.put(entry.getName(),AgentResourceMigration.readBounded(zip,AgentResourceMigration.MAX_BYTES));}return result;
    }
    private static byte[] zip(Map<String,byte[]> entries) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(output)){for(Map.Entry<String,byte[]> entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}return output.toByteArray();
    }
}
