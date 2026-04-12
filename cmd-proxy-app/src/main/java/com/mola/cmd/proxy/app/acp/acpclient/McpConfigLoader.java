package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 配置加载工具类。
 * <p>
 * 从配置文件列表中读取 mcpServers，按优先级合并（后加载的不覆盖先加载的），
 * 并转换为 ACP 协议所需的格式。
 * <p>
 * 提取自 AcpClient，供 AcpClient 和 SubAgentAcpClient 共用。
 */
public final class McpConfigLoader {

    private static final Logger logger = LoggerFactory.getLogger(McpConfigLoader.class);

    private McpConfigLoader() {}

    /**
     * 从多个配置文件中加载并合并 MCP Server 配置。
     *
     * @param configPaths 配置文件路径列表，按优先级从低到高排列
     * @return ACP 协议格式的 mcpServers JsonArray
     */
    public static JsonArray loadFromPaths(List<Path> configPaths) {
        Map<String, JsonObject> serverMap = new LinkedHashMap<>();
        for (Path configPath : configPaths) {
            loadFromSinglePath(configPath, serverMap);
        }
        JsonArray result = new JsonArray();
        serverMap.values().forEach(result::add);
        return result;
    }

    private static void loadFromSinglePath(Path configPath, Map<String, JsonObject> serverMap) {
        if (!Files.exists(configPath)) {
            logger.debug("MCP config not found, skipping: {}", configPath);
            return;
        }
        try {
            String content = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            JsonObject servers = root.getAsJsonObject("mcpServers");
            if (servers == null) {
                logger.debug("No mcpServers in config: {}", configPath);
                return;
            }
            int loaded = 0;
            for (Map.Entry<String, JsonElement> entry : servers.entrySet()) {
                String name = entry.getKey();
                JsonObject serverObj = entry.getValue().getAsJsonObject();
                if (serverObj.has("disabled") && serverObj.get("disabled").getAsBoolean()) {
                    continue;
                }
                if (serverMap.containsKey(name)) {
                    continue;
                }
                JsonObject mcpServer = convertToAcpFormat(name, serverObj);
                if (mcpServer != null) {
                    serverMap.put(name, mcpServer);
                    loaded++;
                }
            }
            logger.info("Loaded {} MCP server(s) from {}", loaded, configPath);
        } catch (Exception e) {
            logger.error("Failed to load MCP config from {}", configPath, e);
        }
    }

    /**
     * 将 mcp.json 格式的 server 配置转换为 ACP 协议格式。
     */
    static JsonObject convertToAcpFormat(String name, JsonObject serverObj) {
        JsonObject acpServer = new JsonObject();
        acpServer.addProperty("name", name);

        if (serverObj.has("url")) {
            acpServer.addProperty("type", "http");
            acpServer.addProperty("url", serverObj.get("url").getAsString());
            if (serverObj.has("headers") && serverObj.get("headers").isJsonObject()) {
                JsonArray headerArray = new JsonArray();
                for (Map.Entry<String, JsonElement> h : serverObj.getAsJsonObject("headers").entrySet()) {
                    JsonObject header = new JsonObject();
                    header.addProperty("name", h.getKey());
                    header.addProperty("value", h.getValue().getAsString());
                    headerArray.add(header);
                }
                acpServer.add("headers", headerArray);
            }
        } else if (serverObj.has("command")) {
            acpServer.addProperty("command", serverObj.get("command").getAsString());
            if (serverObj.has("args") && serverObj.get("args").isJsonArray()) {
                acpServer.add("args", serverObj.getAsJsonArray("args"));
            } else {
                acpServer.add("args", new JsonArray());
            }
            if (serverObj.has("env") && serverObj.get("env").isJsonObject()) {
                JsonArray envArray = new JsonArray();
                for (Map.Entry<String, JsonElement> e : serverObj.getAsJsonObject("env").entrySet()) {
                    JsonObject envVar = new JsonObject();
                    envVar.addProperty("name", e.getKey());
                    envVar.addProperty("value", e.getValue().getAsString());
                    envArray.add(envVar);
                }
                acpServer.add("env", envArray);
            } else {
                acpServer.add("env", new JsonArray());
            }
        } else {
            logger.warn("MCP server '{}' has neither 'url' nor 'command', skipping", name);
            return null;
        }
        return acpServer;
    }

    /**
     * 从配置文件列表中提取 MCP server 名称列表（用于能力反思等场景）。
     */
    public static java.util.List<String> loadServerNames(List<Path> configPaths) {
        Map<String, JsonObject> serverMap = new LinkedHashMap<>();
        for (Path configPath : configPaths) {
            loadFromSinglePath(configPath, serverMap);
        }
        return new java.util.ArrayList<>(serverMap.keySet());
    }
}
