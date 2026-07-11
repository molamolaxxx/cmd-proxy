package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** 支持 [mcp_servers.name] 和 [mcp_servers."name.with.dot"] 两种 Codex TOML table。 */
    private static final Pattern CODEX_MCP_TABLE = Pattern.compile(
            "^\\s*\\[\\s*mcp_servers\\.(?:\\\"([^\\\"]+)\\\"|'([^']+)'|([A-Za-z0-9_-]+))\\s*]\\s*$");

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
            if (configPath.getFileName().toString().toLowerCase().endsWith(".toml")) {
                loadCodexToml(content, configPath, serverMap);
                return;
            }
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            JsonObject servers = root.getAsJsonObject("mcpServers");
            if (servers == null) {
                servers = root.getAsJsonObject("mcp");
            }
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
     * 读取 Codex 项目配置中的 MCP 子集。
     * <p>
     * cmd-proxy 只需要 MCP transport 所需的字段，因此这里有意不引入完整 TOML
     * 依赖：支持 Codex MCP 配置用到的 table、字符串/布尔值、字符串数组和内联表，
     * 其余 Codex 设置会被安全跳过。
     */
    private static void loadCodexToml(String content, Path configPath, Map<String, JsonObject> serverMap) {
        Map<String, JsonObject> configuredServers = new LinkedHashMap<>();
        String currentServerName = null;
        JsonObject currentServer = null;
        String pendingKey = null;
        StringBuilder pendingValue = new StringBuilder();

        String[] lines = content.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = stripTomlComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }

            if (pendingKey != null) {
                pendingValue.append('\n').append(line);
                if (!isCompleteTomlValue(pendingValue.toString())) {
                    continue;
                }
                currentServer.add(pendingKey, parseTomlValue(pendingValue.toString()));
                pendingKey = null;
                pendingValue.setLength(0);
                continue;
            }

            Matcher tableMatcher = CODEX_MCP_TABLE.matcher(line);
            if (tableMatcher.matches()) {
                currentServerName = firstNonNull(tableMatcher.group(1), tableMatcher.group(2), tableMatcher.group(3));
                currentServer = configuredServers.get(currentServerName);
                if (currentServer == null) {
                    currentServer = new JsonObject();
                    configuredServers.put(currentServerName, currentServer);
                }
                continue;
            }
            if (line.startsWith("[")) {
                currentServerName = null;
                currentServer = null;
                continue;
            }
            if (currentServer == null) {
                continue;
            }

            int equalsIndex = findTopLevelEquals(line);
            if (equalsIndex <= 0) {
                logger.warn("Ignoring invalid Codex MCP config line in {}: {}", configPath, rawLine);
                continue;
            }
            String key = unquoteTomlToken(line.substring(0, equalsIndex).trim());
            String value = line.substring(equalsIndex + 1).trim();
            if (!isCompleteTomlValue(value)) {
                pendingKey = key;
                pendingValue.append(value);
            } else {
                currentServer.add(key, parseTomlValue(value));
            }
        }

        if (pendingKey != null) {
            logger.warn("Ignoring unfinished Codex MCP value '{}' in {}", pendingKey, configPath);
        }

        int loaded = 0;
        for (Map.Entry<String, JsonObject> entry : configuredServers.entrySet()) {
            String name = entry.getKey();
            JsonObject serverObj = entry.getValue();
            if (serverObj.has("enabled") && !serverObj.get("enabled").getAsBoolean()) {
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
        logger.info("Loaded {} Codex TOML MCP server(s) from {}", loaded, configPath);
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
            JsonObject headers = null;
            if (serverObj.has("headers") && serverObj.get("headers").isJsonObject()) {
                headers = serverObj.getAsJsonObject("headers");
            } else if (serverObj.has("http_headers") && serverObj.get("http_headers").isJsonObject()) {
                // Codex TOML 使用 http_headers，ACP 使用 headers 数组。
                headers = serverObj.getAsJsonObject("http_headers");
            }
            if (headers != null) {
                JsonArray headerArray = new JsonArray();
                for (Map.Entry<String, JsonElement> h : headers.entrySet()) {
                    JsonObject header = new JsonObject();
                    header.addProperty("name", h.getKey());
                    header.addProperty("value", h.getValue().getAsString());
                    headerArray.add(header);
                }
                acpServer.add("headers", headerArray);
            }
        } else if (serverObj.has("command")) {
            // command 可能是字符串（kiro 格式）或数组（opencode 格式）
            JsonElement cmdElem = serverObj.get("command");
            if (cmdElem.isJsonArray()) {
                JsonArray cmdArray = cmdElem.getAsJsonArray();
                if (cmdArray.size() == 0) {
                    logger.warn("MCP server '{}' has empty command array, skipping", name);
                    return null;
                }
                acpServer.addProperty("command", cmdArray.get(0).getAsString());
                JsonArray args = new JsonArray();
                for (int i = 1; i < cmdArray.size(); i++) {
                    args.add(cmdArray.get(i).getAsString());
                }
                acpServer.add("args", args);
            } else {
                acpServer.addProperty("command", cmdElem.getAsString());
                if (serverObj.has("args") && serverObj.get("args").isJsonArray()) {
                    acpServer.add("args", serverObj.getAsJsonArray("args"));
                } else {
                    acpServer.add("args", new JsonArray());
                }
            }
            // env: opencode 用 "environment"，kiro 用 "env"
            JsonObject envObj = null;
            if (serverObj.has("env") && serverObj.get("env").isJsonObject()) {
                envObj = serverObj.getAsJsonObject("env");
            } else if (serverObj.has("environment") && serverObj.get("environment").isJsonObject()) {
                envObj = serverObj.getAsJsonObject("environment");
            }
            if (envObj != null) {
                JsonArray envArray = new JsonArray();
                for (Map.Entry<String, JsonElement> e : envObj.entrySet()) {
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

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) return value;
        }
        return null;
    }

    /** 移除 TOML 注释，但保留字符串中的 #。 */
    private static String stripTomlComment(String value) {
        boolean inBasicString = false;
        boolean inLiteralString = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (inBasicString && c == '\\' && !escaped) {
                escaped = true;
                continue;
            }
            if (!inLiteralString && c == '"' && !escaped) {
                inBasicString = !inBasicString;
            } else if (!inBasicString && c == '\'') {
                inLiteralString = !inLiteralString;
            } else if (c == '#' && !inBasicString && !inLiteralString) {
                return value.substring(0, i);
            }
            escaped = false;
        }
        return value;
    }

    private static int findTopLevelEquals(String value) {
        boolean inBasicString = false;
        boolean inLiteralString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (inBasicString && c == '\\' && !escaped) {
                escaped = true;
                continue;
            }
            if (!inLiteralString && c == '"' && !escaped) {
                inBasicString = !inBasicString;
            } else if (!inBasicString && c == '\'') {
                inLiteralString = !inLiteralString;
            } else if (!inBasicString && !inLiteralString) {
                if (c == '[' || c == '{') depth++;
                else if (c == ']' || c == '}') depth--;
                else if (c == '=' && depth == 0) return i;
            }
            escaped = false;
        }
        return -1;
    }

    private static boolean isCompleteTomlValue(String value) {
        boolean inBasicString = false;
        boolean inLiteralString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (inBasicString && c == '\\' && !escaped) {
                escaped = true;
                continue;
            }
            if (!inLiteralString && c == '"' && !escaped) {
                inBasicString = !inBasicString;
            } else if (!inBasicString && c == '\'') {
                inLiteralString = !inLiteralString;
            } else if (!inBasicString && !inLiteralString) {
                if (c == '[' || c == '{') depth++;
                else if (c == ']' || c == '}') depth--;
            }
            escaped = false;
        }
        return !inBasicString && !inLiteralString && depth == 0;
    }

    private static JsonElement parseTomlValue(String rawValue) {
        String value = rawValue.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            try {
                return JsonParser.parseString(value);
            } catch (Exception ignored) {
                return new JsonPrimitive(value.substring(1, value.length() - 1));
            }
        }
        if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
            return new JsonPrimitive(value.substring(1, value.length() - 1));
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            JsonArray array = new JsonArray();
            for (String item : splitTomlTopLevel(value.substring(1, value.length() - 1), ',')) {
                if (!item.trim().isEmpty()) array.add(parseTomlValue(item));
            }
            return array;
        }
        if (value.startsWith("{") && value.endsWith("}")) {
            JsonObject object = new JsonObject();
            for (String item : splitTomlTopLevel(value.substring(1, value.length() - 1), ',')) {
                int equalsIndex = findTopLevelEquals(item);
                if (equalsIndex > 0) {
                    object.add(unquoteTomlToken(item.substring(0, equalsIndex).trim()),
                            parseTomlValue(item.substring(equalsIndex + 1)));
                }
            }
            return object;
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return new JsonPrimitive(Boolean.parseBoolean(value));
        }
        return new JsonPrimitive(value);
    }

    private static List<String> splitTomlTopLevel(String value, char separator) {
        List<String> parts = new ArrayList<>();
        boolean inBasicString = false;
        boolean inLiteralString = false;
        boolean escaped = false;
        int depth = 0;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (inBasicString && c == '\\' && !escaped) {
                escaped = true;
                continue;
            }
            if (!inLiteralString && c == '"' && !escaped) {
                inBasicString = !inBasicString;
            } else if (!inBasicString && c == '\'') {
                inLiteralString = !inLiteralString;
            } else if (!inBasicString && !inLiteralString) {
                if (c == '[' || c == '{') depth++;
                else if (c == ']' || c == '}') depth--;
                else if (c == separator && depth == 0) {
                    parts.add(value.substring(start, i));
                    start = i + 1;
                }
            }
            escaped = false;
        }
        parts.add(value.substring(start));
        return parts;
    }

    private static String unquoteTomlToken(String value) {
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
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
