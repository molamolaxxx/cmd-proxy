package com.mola.cmd.proxy.app.mcpclient;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * McpClient 工厂，负责从 ~/.cmd-proxy/mcp.json 读取配置，
 * 初始化所有非禁用的 McpClient 并统一管理其生命周期。
 */
public class McpClientFactory implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(McpClientFactory.class);
    private static final String CONFIG_DIR = ".cmd-proxy";
    private static final String CONFIG_FILE = "mcp.json";

    private static volatile McpClientFactory instance;

    /** serverName -> McpClient */
    private final ConcurrentHashMap<String, McpClient> clients = new ConcurrentHashMap<>();
    /** serverName -> McpClientConfig */
    private final ConcurrentHashMap<String, McpClientConfig> configs = new ConcurrentHashMap<>();
    /** 全局配置（~/.cmd-proxy/mcp.json）加载的 server 名称 */
    private final Set<String> globalServerNames = ConcurrentHashMap.newKeySet();

    private final Gson gson = new GsonBuilder().create();

    private McpClientFactory() {}

    public static McpClientFactory getInstance() {
        if (instance == null) {
            synchronized (McpClientFactory.class) {
                if (instance == null) {
                    instance = new McpClientFactory();
                    instance.loadMcpServers();
                }
            }
        }
        return instance;
    }

    /**
     * 从 ~/.cmd-proxy/mcp.json 读取配置并初始化所有非禁用的 client
     */
    public void init() throws IOException {
        Path configPath = Paths.get(System.getProperty("user.home"), CONFIG_DIR, CONFIG_FILE);
        initFromPath(configPath);
        // 记录全局配置加载的 server 名称
        globalServerNames.addAll(clients.keySet());
    }

    /**
     * 从指定路径的 mcp.json 读取配置并初始化所有非禁用的 client。
     * 已存在同名 client 时跳过，不会重复创建。
     * @param configPath mcp.json 文件路径
     */
    public void initFromPath(Path configPath) throws IOException {
        if (!Files.exists(configPath)) {
            logger.warn("MCP config file not found: {}", configPath);
            return;
        }

        String content = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(content).getAsJsonObject();
        JsonObject mcpServers = root.getAsJsonObject("mcpServers");
        if (mcpServers == null) {
            logger.warn("No mcpServers found in config: {}", configPath);
            return;
        }

        for (Map.Entry<String, JsonElement> entry : mcpServers.entrySet()) {
            String serverName = entry.getKey();
            JsonObject serverObj = entry.getValue().getAsJsonObject();

            if (serverObj.has("disabled") && serverObj.get("disabled").getAsBoolean()) {
                logger.info("Skipping disabled MCP server: {}", serverName);
                continue;
            }

            if (clients.containsKey(serverName)) {
                logger.info("MCP client already exists, skipping: {}", serverName);
                continue;
            }

            try {
                McpClientConfig config = parseConfig(serverName, serverObj);
                configs.put(serverName, config);

                McpClient client = config.isHttpMode()
                        ? new HttpMcpClient(config)
                        : new StdioMcpClient(config);
                client.start();
                clients.put(serverName, client);
                logger.info("MCP client initialized from {}: {}", configPath, serverName);
            } catch (Exception e) {
                logger.error("Failed to initialize MCP client: {}", serverName, e);
            }
        }

        logger.info("McpClientFactory loaded from {} with {} client(s): {}", configPath, clients.size(), clients.keySet());
    }

    private McpClientConfig parseConfig(String name, JsonObject obj) {
        // stdio 模式字段
        String command = obj.has("command") ? obj.get("command").getAsString() : null;

        List<String> args = new ArrayList<>();
        if (obj.has("args")) {
            for (JsonElement e : obj.getAsJsonArray("args")) {
                args.add(e.getAsString());
            }
        }

        Map<String, String> env = new HashMap<>();
        if (obj.has("env") && obj.get("env").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("env").entrySet()) {
                env.put(e.getKey(), e.getValue().getAsString());
            }
        }

        // http 模式字段
        String url = obj.has("url") ? obj.get("url").getAsString() : null;

        Map<String, String> headers = new HashMap<>();
        if (obj.has("headers") && obj.get("headers").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("headers").entrySet()) {
                headers.put(e.getKey(), e.getValue().getAsString());
            }
        }

        // 通用字段
        List<String> autoApprove = new ArrayList<>();
        if (obj.has("autoApprove")) {
            for (JsonElement e : obj.getAsJsonArray("autoApprove")) {
                autoApprove.add(e.getAsString());
            }
        }

        return new McpClientConfig(name, command, args, env, url, headers, autoApprove);
    }

    /**
     * 根据 serverName 获取已初始化的 client
     */
    public McpClient getClient(String serverName) {
        return clients.get(serverName);
    }

    /**
     * 获取所有已初始化的 client
     */
    public Map<String, McpClient> getAllClients() {
        return Collections.unmodifiableMap(clients);
    }

    /**
     * 获取所有已初始化的 server 名称
     */
    public Set<String> getServerNames() {
        return Collections.unmodifiableSet(clients.keySet());
    }

    /**
     * 根据 toolName 查找提供该工具的 client
     */
    public McpClient findClientByTool(String toolName) {
        for (McpClient client : clients.values()) {
            if (client.getToolNames().contains(toolName)) {
                return client;
            }
        }
        return null;
    }

    /**
     * 获取所有 client 的工具名称汇总（serverName -> toolNames）
     */
    public Map<String, List<String>> getAllToolNames() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getToolNames());
        }
        return result;
    }

    /**
     * 加载所有 MCP Server 信息，返回 markdown 表格。
     * 如果 factory 未初始化则先执行 init()。
     */
    public String loadMcpServers() {
        if (clients.isEmpty()) {
            try {
                init();
            } catch (IOException e) {
                logger.error("Failed to init McpClientFactory", e);
                return "McpClientFactory 初始化失败: " + e.getMessage();
            }
        }
        return loadMcpServersByNames(globalServerNames);
    }

    /**
     * 加载指定 server 列表的 MCP Server 信息，返回 markdown 表格。
     * serverNames 为 null 时加载全部已初始化的 server。
     * 列: serverName | toolName | description | inputSchema | callExample
     */
    public String loadMcpServersByNames(Set<String> serverNames) {
        if (serverNames == null || serverNames.isEmpty()) {
            return "暂无可用的 MCP Server";
        }

        Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
        StringBuilder sb = new StringBuilder();
        sb.append("| serverName | toolName | description | inputSchema | callExample |\n");
        sb.append("|---|---|---|---|---|\n");

        for (String serverName : serverNames) {
            McpClient client = clients.get(serverName);
            if (client == null) {
                sb.append("| ").append(serverName).append(" | - | 未成功加载 | - | - |\n");
                continue;
            }
            JsonArray tools = client.getTools();
            if (tools == null || tools.size() == 0) {
                sb.append("| ").append(serverName).append(" | - | - | - | - |\n");
                continue;
            }
            for (int i = 0; i < tools.size(); i++) {
                JsonObject tool = tools.get(i).getAsJsonObject();
                String toolName = tool.has("name") ? tool.get("name").getAsString() : "-";
                String description = tool.has("description") ? tool.get("description").getAsString() : "-";
                description = description
                        .replace("|", "\\|")
                        .replace("\n", "<br>");
                String inputSchema = "-";
                String callExample = "-";
                if (tool.has("inputSchema")) {
                    JsonObject schema = tool.getAsJsonObject("inputSchema");
                    inputSchema = prettyGson.toJson(schema)
                            .replace("|", "\\|")
                            .replace("\n", "<br>");
                    JsonObject exampleArgs = buildExampleFromSchema(schema);
                    callExample = "callMcpServer " + serverName + " " + toolName + " " + gson.toJson(exampleArgs);
                }
                sb.append("| ").append(serverName)
                  .append(" | ").append(toolName)
                  .append(" | ").append(description.replace("|", "\\|"))
                  .append(" | ").append(inputSchema)
                  .append(" | ").append(callExample.replace("|", "\\|"))
                  .append(" |\n");
            }
        }

        return sb.toString();
    }

        /**
         * 根据 JSON Schema 的 properties 生成示例 JSON 对象。
         * 按类型填充占位值: string->"", integer/number->0, boolean->false, array->[], object->{}
         */
        private JsonObject buildExampleFromSchema(JsonObject schema) {
            JsonObject example = new JsonObject();
            if (!schema.has("properties")) {
                return example;
            }
            JsonObject properties = schema.getAsJsonObject("properties");
            for (Map.Entry<String, JsonElement> prop : properties.entrySet()) {
                String key = prop.getKey();
                JsonObject propDef = prop.getValue().getAsJsonObject();
                String type = propDef.has("type") ? propDef.get("type").getAsString() : "string";
                switch (type) {
                    case "integer":
                    case "number":
                        example.addProperty(key, 0);
                        break;
                    case "boolean":
                        example.addProperty(key, false);
                        break;
                    case "array":
                        example.add(key, new JsonArray());
                        break;
                    case "object":
                        example.add(key, new JsonObject());
                        break;
                    default:
                        // string 及其他类型，用 description 作为提示，否则空串
                        String hint = propDef.has("description") ? propDef.get("description").getAsString() : "";
                        example.addProperty(key, hint);
                        break;
                }
            }
            return example;
        }


    /**
     * 调用指定 server 的指定工具。
     *
     * @param serverName MCP server 名称
     * @param toolName   工具名称
     * @param inputJson  参数 JSON 字符串，如 {"sql":"SELECT 1"}
     * @return 工具调用结果文本
     */
    public String callMcpServer(String serverName, String toolName, String inputJson) {
        McpClient client = clients.get(serverName);
        if (client == null) {
            return "错误: 未找到 MCP server [" + serverName + "]，请先执行 loadMcpServers";
        }

        try {
            Map<String, Object> arguments = new HashMap<>();
            if (inputJson != null && !inputJson.trim().isEmpty()) {
                JsonObject jsonObj = JsonParser.parseString(inputJson).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : jsonObj.entrySet()) {
                    arguments.put(entry.getKey(), toJavaObject(entry.getValue()));
                }
            }

            JsonObject result = client.callTool(toolName, arguments);
            String text = client.extractTextContent(result);
            return text != null ? text : gson.toJson(result);
        } catch (Exception e) {
            logger.error("callMcpServer failed: server={}, tool={}", serverName, toolName, e);
            return "调用失败: " + e.getMessage();
        }
    }

    /**
     * 将 JsonElement 转为 Java 基本类型，供 callTool 的 Map 使用
     */
    private Object toJavaObject(JsonElement element) {
        if (element.isJsonPrimitive()) {
            JsonPrimitive p = element.getAsJsonPrimitive();
            if (p.isNumber()) return p.getAsNumber();
            if (p.isBoolean()) return p.getAsBoolean();
            return p.getAsString();
        }
        if (element.isJsonArray()) return element;
        if (element.isJsonObject()) return element;
        return null;
    }

    /**
     * 关闭并移除指定 client
     */
    public void removeClient(String serverName) {
        McpClient client = clients.remove(serverName);
        configs.remove(serverName);
        globalServerNames.remove(serverName);
        if (client != null) {
            try {
                client.close();
                logger.info("MCP client removed: {}", serverName);
            } catch (IOException e) {
                logger.warn("Error closing MCP client: {}", serverName, e);
            }
        }
    }

    /**
     * 关闭所有 client
     */
    @Override
    public void close() throws IOException {
        logger.info("Closing McpClientFactory, shutting down {} client(s)...", clients.size());
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            try {
                entry.getValue().close();
                logger.info("Closed MCP client: {}", entry.getKey());
            } catch (IOException e) {
                logger.warn("Error closing MCP client: {}", entry.getKey(), e);
            }
        }
        clients.clear();
        configs.clear();
        globalServerNames.clear();
    }


    /**
     * 测试入口：初始化 factory，打印所有 client 信息，
     * 对每个 client 调用第一个工具做简单验证，最后关闭。
     */
    public static void main(String[] args) {
        McpClientFactory factory = McpClientFactory.getInstance();
        try {
            System.out.println("====== 初始化 McpClientFactory ======");
            factory.init();

            Set<String> serverNames = factory.getServerNames();
            System.out.println("已初始化的 server 数量: " + serverNames.size());
            System.out.println("server 列表: " + serverNames);

            // 打印每个 server 的工具列表
            System.out.println(factory.loadMcpServers());

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                factory.close();
                System.out.println("\n====== McpClientFactory 已关闭 ======");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}
