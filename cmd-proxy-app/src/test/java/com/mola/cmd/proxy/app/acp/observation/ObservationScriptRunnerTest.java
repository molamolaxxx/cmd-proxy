package com.mola.cmd.proxy.app.acp.observation;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class ObservationScriptRunnerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void returnsExactAsyncStringAndSeparatesLogs() throws Exception {
        Path workspace = temporary.newFolder().toPath();
        Files.write(workspace.resolve("value.txt"), "\n中文结果\n ".getBytes(StandardCharsets.UTF_8));
        JsonObject result =
                new ObservationScriptRunner()
                        .execute(
                                "module.exports=async()=>{console.log('日志');process.stdout.write('直接日志');return"
                                    + " require('fs').readFileSync('value.txt','utf8')}",
                                workspace);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals("\n中文结果\n ", result.get("result").getAsString());
        assertTrue(result.get("logs").getAsString().contains("日志"));
        assertTrue(result.get("logs").getAsString().contains("直接日志"));
        assertTrue(
                new ObservationScriptRunner()
                        .execute("module.exports=()=>''", workspace)
                        .get("success")
                        .getAsBoolean());
    }

    @Test
    public void reportsStackWrongTypesTimeoutAndOversize() throws Exception {
        Path workspace = temporary.newFolder().toPath();
        ObservationScriptRunner runner = new ObservationScriptRunner();
        JsonObject failure =
                runner.execute("module.exports=()=>{throw new Error('boom')}", workspace);
        assertFalse(failure.get("success").getAsBoolean());
        assertTrue(failure.get("error").getAsString().contains(".starweave-observe.cjs"));
        assertFalse(
                runner.execute("module.exports=()=>42", workspace).get("success").getAsBoolean());
        assertFalse(
                runner.execute("module.exports=()=> 'x'.repeat(1048577)", workspace)
                        .get("success")
                        .getAsBoolean());
        JsonObject timeout =
                new ObservationScriptRunner(250)
                        .execute(
                                "module.exports=()=>new Promise(()=>{}) ; setInterval(()=>{},100)",
                                workspace);
        assertFalse(timeout.get("success").getAsBoolean());
        assertTrue(timeout.get("error").getAsString().contains("超时"));
        assertFalse(runner.execute("process.exit(0)", workspace).get("success").getAsBoolean());
    }

    @Test
    public void flushesLargeResultsBeforeExiting() throws Exception {
        Path workspace = temporary.newFolder().toPath();
        JsonObject result =
                new ObservationScriptRunner()
                        .execute("module.exports=()=> '中文\\n'.repeat(100000)", workspace);
        assertTrue(
                result.toString().substring(0, Math.min(200, result.toString().length())),
                result.get("success").getAsBoolean());
        assertEquals(300000, result.get("result").getAsString().length());
        assertTrue(result.get("result").getAsString().endsWith("中文\n"));
    }

    @Test
    public void resolvesWorkspaceNodeDependencies() throws Exception {
        Path workspace = temporary.newFolder().toPath();
        Path dependency = workspace.resolve("node_modules/my-source");
        Files.createDirectories(dependency);
        Files.write(
                dependency.resolve("index.js"),
                "module.exports='本地依赖'".getBytes(StandardCharsets.UTF_8));
        JsonObject result =
                new ObservationScriptRunner()
                        .execute("module.exports=()=>require('my-source')", workspace);
        assertEquals("本地依赖", result.get("result").getAsString());
    }
}
