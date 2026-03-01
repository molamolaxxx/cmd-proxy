package com.mola.cmd.proxy.app.mcpclient;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通用 MCP Client 抽象基类。
 * <p>
 * 定义 MCP 协议的完整握手流程:
 * <ol>
 *   <li>initialize（协议版本协商 + 能力声明）</li>
 *   <li>notifications/initialized（通知 Server 初始化完成）</li>
 *   <li>tools/list（获取工具列表）</li>
 *   <li>tools/call（调用具体工具）</li>
 * </ol>
 * <p>
 * 子类需实现具体的通信方式（stdio / http）。
 */
public abstract class McpClient implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(McpClient.class);
    protected static final String JSONRPC_VERSION = "2.0";
    protected static final String CLIENT_NAME = "mcp-java-client";
    protected static final String CLIENT_VERSION = "1.0.0";
    protected static final String PROTOCOL_VERSION = "2024-11-05";

    protected final McpClientConfig config;
    protected final Gson gson = new GsonBuilder().create();
    protected final AtomicInteger idCounter = new AtomicInteger(0);

    /** Server 返回的协商后协议版本 */
    protected String negotiatedProtocolVersion;
    /** Server 信息 */
    protected JsonObject serverInfo;
    /** Server 能力 */
    protected JsonObject serverCapabilities;
    /** 可用工具列表 */
    protected JsonArray tools;

    public McpClient(McpClientConfig config) {
        this.config = config;
    }

    // ==================== 生命周期 ====================

    /**
     * 启动 MCP Client 并完成完整的握手流程:
     * connect → initialize → notifications/initialized → tools/list
     */
    public void start() throws IOException {
        connect();
        initialize();
        sendInitializedNotification();
        listTools();
        logger.info("MCP Client fully connected. Available tools: {}", getToolNames());
    }

    /**
     * 建立底层连接（子类实现：启动子进程 / 建立HTTP会话等）
     */
    protected abstract void connect() throws IOException;

    /**
     * 发送 JSON-RPC request 并等待响应（子类实现具体通信方式）
     */
    protected abstract JsonObject sendRequest(String method, JsonObject params) throws IOException;

    /**
     * 发送 JSON-RPC notification（无需响应）
     */
    protected abstract void sendNotification(String method) throws IOException;

    // ==================== 协议步骤 ====================

    /**
     * initialize — 协议版本协商 + 能力声明
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
     * notifications/initialized — 通知 Server 客户端初始化完成
     */
    private void sendInitializedNotification() throws IOException {
        sendNotification("notifications/initialized");
        logger.info("Sent initialized notification");
    }

    /**
     * tools/list — 获取 Server 提供的所有工具
     */
    public void listTools() throws IOException {
        JsonObject response = sendRequest("tools/list", new JsonObject());
        JsonObject result = response.getAsJsonObject("result");
        tools = result.getAsJsonArray("tools");
        logger.info("Discovered {} tool(s)", tools.size());
    }

    /**
     * tools/call — 调用指定工具
     *
     * @param toolName  工具名称
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

    // ==================== 工具方法 ====================

    protected JsonObject buildJsonRpcRequest(String method, JsonObject params) {
        int id = idCounter.getAndIncrement();
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", JSONRPC_VERSION);
        request.addProperty("method", method);
        request.addProperty("id", id);
        request.add("params", params);
        return request;
    }

    protected JsonObject buildJsonRpcNotification(String method) {
        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", JSONRPC_VERSION);
        notification.addProperty("method", method);
        return notification;
    }

    protected static String joinStrings(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
