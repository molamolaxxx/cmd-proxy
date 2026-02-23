package com.mola.cmd.proxy.app.mcpclient;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通用 MCP Client — 通过 stdio 与 MCP Server 子进程通信。
 * <p>
 * 完整实现 MCP 协议握手流程:
 * <ol>
 *   <li>启动子进程</li>
 *   <li>initialize（协议版本协商 + 能力声明）</li>
 *   <li>notifications/initialized（通知 Server 初始化完成）</li>
 *   <li>tools/list（获取工具列表）</li>
 *   <li>tools/call（调用具体工具）</li>
 * </ol>
 * <p>
 * 通信协议: JSON-RPC 2.0 over stdio，每条消息以换行符分隔。
 */
public class McpClient implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(McpClient.class);
    private static final String JSONRPC_VERSION = "2.0";
    private static final String CLIENT_NAME = "mcp-java-client";
    private static final String CLIENT_VERSION = "1.0.0";
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpClientConfig config;
    private final Gson gson = new GsonBuilder().create();
    private final AtomicInteger idCounter = new AtomicInteger(0);

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private BufferedReader errorReader;

    /** Server 返回的协商后协议版本 */
    private String negotiatedProtocolVersion;
    /** Server 信息 */
    private JsonObject serverInfo;
    /** Server 能力 */
    private JsonObject serverCapabilities;
    /** 可用工具列表 */
    private JsonArray tools;

    public McpClient(McpClientConfig config) {
        this.config = config;
    }

    // ==================== 生命周期 ====================

    /**
     * 启动 MCP Server 子进程并完成完整的握手流程:
     * initialize → notifications/initialized → tools/list
     */
    public void start() throws IOException {
        startProcess();
        initialize();
        sendInitializedNotification();
        listTools();
        logger.info("MCP Client fully connected. Available tools: {}", getToolNames());
    }

    private void startProcess() throws IOException {
        List<String> command = config.buildCommand();
        logger.info("Starting MCP Server process: {}", joinStrings(command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        // 补充用户级 bin 路径到 PATH，解决从 IDE 启动时找不到 uvx 等命令的问题
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

    // ==================== 协议步骤 ====================

    /**
     * 步骤①② initialize — 协议版本协商 + 能力声明
     */
    private void initialize() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);

        JsonObject capabilities = new JsonObject();
        params.add("capabilities", capabilities);

        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", CLIENT_NAME);
        clientInfo.addProperty("version", CLIENT_VERSION);
        params.add("clientInfo", clientInfo);

        JsonObject response = sendRequest("initialize", params);
        JsonObject result = response.getAsJsonObject("result");

        negotiatedProtocolVersion = result.get("protocolVersion").getAsString();
        serverCapabilities = result.getAsJsonObject("capabilities");
        serverInfo = result.getAsJsonObject("serverInfo");

        logger.info("Initialize complete — server: {}, protocol: {}",
                serverInfo, negotiatedProtocolVersion);
    }

    /**
     * 步骤③ notifications/initialized — 通知 Server 客户端初始化完成
     */
    private void sendInitializedNotification() throws IOException {
        sendNotification("notifications/initialized");
        logger.info("Sent initialized notification");
    }

    /**
     * 步骤④⑤ tools/list — 获取 Server 提供的所有工具
     */
    public void listTools() throws IOException {
        JsonObject response = sendRequest("tools/list", new JsonObject());
        JsonObject result = response.getAsJsonObject("result");
        tools = result.getAsJsonArray("tools");
        logger.info("Discovered {} tool(s)", tools.size());
    }

    /**
     * 步骤⑥⑦ tools/call — 调用指定工具
     *
     * @param toolName  工具名称，如 "mysql_list_tables"
     * @param arguments 工具参数
     * @return Server 返回的 result 对象
     */
    public JsonObject callTool(String toolName, Map<String, Object> arguments) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("name", toolName);
        Map<String, Object> args = arguments != null ? arguments : new HashMap<String, Object>();
        params.add("arguments", gson.toJsonTree(args));

        logger.info("Calling tool: {} with arguments: {}", toolName, arguments);
        JsonObject response = sendRequest("tools/call", params);
        JsonObject result = response.getAsJsonObject("result");

        if (result.has("isError") && result.get("isError").getAsBoolean()) {
            logger.error("Tool call failed: {}", result);
        }
        return result;
    }

    /**
     * 从 tools/call 的 result 中提取文本内容
     */
    public String extractTextContent(JsonObject callResult) {
        JsonArray content = callResult.getAsJsonArray("content");
        if (content != null && content.size() > 0) {
            return content.get(0).getAsJsonObject().get("text").getAsString();
        }
        return null;
    }

    // ==================== JSON-RPC 通信层 ====================

    private JsonObject sendRequest(String method, JsonObject params) throws IOException {
        int id = idCounter.getAndIncrement();
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", JSONRPC_VERSION);
        request.addProperty("method", method);
        request.addProperty("id", id);
        request.add("params", params);

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

            // 跳过非JSON行（如server的stderr混入stdout的日志输出）
            String trimmedLine = line.trim();
            if (!trimmedLine.startsWith("{")) {
                logger.warn("Skipping non-JSON line: {}", line);
                continue;
            }

            JsonObject resp;
            try {
                resp = JsonParser.parseString(trimmedLine).getAsJsonObject();
            } catch (com.google.gson.JsonSyntaxException e) {
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

    private void sendNotification(String method) throws IOException {
        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", JSONRPC_VERSION);
        notification.addProperty("method", method);

        String json = gson.toJson(notification);
        logger.debug(">>> (notification) {}", json);

        writer.write(json);
        writer.newLine();
        writer.flush();
    }

    // ==================== Getter ====================

    public List<String> getToolNames() {
        if (tools == null) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            names.add(tools.get(i).getAsJsonObject().get("name").getAsString());
        }
        return names;
    }

    public JsonArray getTools() {
        return tools;
    }

    public String getNegotiatedProtocolVersion() {
        return negotiatedProtocolVersion;
    }

    public JsonObject getServerInfo() {
        return serverInfo;
    }

    public JsonObject getServerCapabilities() {
        return serverCapabilities;
    }

    // ==================== 关闭 ====================

    @Override
    public void close() throws IOException {
        logger.info("Closing MCP Client...");
        try {
            if (writer != null) writer.close();
        } catch (IOException e) {
            logger.warn("Error closing writer", e);
        }
        try {
            if (reader != null) reader.close();
        } catch (IOException e) {
            logger.warn("Error closing reader", e);
        }
        try {
            if (errorReader != null) errorReader.close();
        } catch (IOException e) {
            logger.warn("Error closing errorReader", e);
        }
        if (process != null && process.isAlive()) {
            process.destroy();
            logger.info("MCP Server process destroyed");
        }
    }

    // ==================== 工具方法 ====================

    private static String joinStrings(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
