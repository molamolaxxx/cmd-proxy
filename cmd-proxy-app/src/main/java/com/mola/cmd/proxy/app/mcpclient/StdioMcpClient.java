package com.mola.cmd.proxy.app.mcpclient;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 基于 stdio 的 MCP Client 实现。
 * 通过子进程的 stdin/stdout 进行 JSON-RPC 2.0 通信。
 */
public class StdioMcpClient extends McpClient {

    private static final Logger logger = LoggerFactory.getLogger(StdioMcpClient.class);

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private BufferedReader errorReader;

    public StdioMcpClient(McpClientConfig config) {
        super(config);
    }

    @Override
    protected void connect() throws IOException {
        List<String> command = config.buildCommand();
        logger.info("Starting MCP Server process: {}", joinStrings(command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        // 补充用户级 bin 路径到 PATH
        String home = System.getProperty("user.home");
        String currentPath = pb.environment().getOrDefault("PATH", "");
        String extraPaths = home + "/.local/bin"
                + File.pathSeparator + home + "/.cargo/bin"
                + File.pathSeparator + "/usr/local/bin";
        if (!currentPath.contains(home + "/.local/bin")) {
            pb.environment().put("PATH", extraPaths + File.pathSeparator + currentPath);
        }

        for (Map.Entry<String, String> entry : config.getEnv().entrySet()) {
            pb.environment().put(entry.getKey(), entry.getValue());
        }

        process = pb.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));

        // stderr 日志转发线程
        Thread stderrThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        logger.debug("[SERVER STDERR] {}", line);
                    }
                } catch (IOException e) {
                    // 进程关闭时正常退出
                }
            }
        }, "mcp-stderr-reader");
        stderrThread.setDaemon(true);
        stderrThread.start();

        logger.info("MCP Server process started");
    }

    @Override
    protected JsonObject sendRequest(String method, JsonObject params) throws IOException {
        JsonObject request = buildJsonRpcRequest(method, params);
        int id = request.get("id").getAsInt();
        String json = gson.toJson(request);
        logger.debug(">>> [id={}] {}", id, json);

        writer.write(json);
        writer.newLine();
        writer.flush();

        // 读取响应（跳过通知消息，匹配 id）
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("MCP Server process closed unexpectedly");
            }
            logger.debug("<<< {}", line);

            String trimmedLine = line.trim();
            if (!trimmedLine.startsWith("{")) {
                logger.warn("Skipping non-JSON line: {}", line);
                continue;
            }

            JsonObject resp;
            try {
                resp = JsonParser.parseString(trimmedLine).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                logger.warn("Skipping malformed JSON line: {}", line);
                continue;
            }

            if (!resp.has("id")) {
                logger.debug("Skipping notification: {}", resp.get("method"));
                continue;
            }
            if (resp.get("id").getAsInt() == id) {
                if (resp.has("error")) {
                    throw new IOException("JSON-RPC error: " + resp.get("error"));
                }
                return resp;
            }
            logger.warn("Received response with unexpected id={}, expected={}", resp.get("id"), id);
        }
    }

    @Override
    protected void sendNotification(String method) throws IOException {
        JsonObject notification = buildJsonRpcNotification(method);
        String json = gson.toJson(notification);
        logger.debug(">>> (notification) {}", json);

        writer.write(json);
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        logger.info("Closing StdioMcpClient...");
        try { if (writer != null) writer.close(); } catch (IOException e) { logger.warn("Error closing writer", e); }
        try { if (reader != null) reader.close(); } catch (IOException e) { logger.warn("Error closing reader", e); }
        try { if (errorReader != null) errorReader.close(); } catch (IOException e) { logger.warn("Error closing errorReader", e); }
        if (process != null && process.isAlive()) {
            process.destroy();
            logger.info("MCP Server process destroyed");
        }
    }
}
