package com.mola.cmd.proxy.app.mcpclient;

import java.util.*;

/**
 * MCP Server 的连接配置，对应 mcp.json 中的一个 server 配置项。
 * <p>
 * 支持两种模式:
 * <ul>
 *   <li>stdio 模式: 需要 command + args，通过子进程通信</li>
 *   <li>http 模式: 需要 url（+ 可选 headers），通过 HTTP 通信</li>
 * </ul>
 */
public class McpClientConfig {

    private final String name;

    // stdio 模式字段
    private final String command;
    private final List<String> args;
    private final Map<String, String> env;

    // http 模式字段
    private final String url;
    private final Map<String, String> headers;

    // 通用字段
    private final List<String> autoApprove;

    /**
     * stdio 模式构造器
     */
    public McpClientConfig(String name, String command, List<String> args,
                           Map<String, String> env, List<String> autoApprove) {
        this(name, command, args, env, null, null, autoApprove);
    }

    /**
     * 全参数构造器
     */
    public McpClientConfig(String name, String command, List<String> args,
                           Map<String, String> env, String url, Map<String, String> headers,
                           List<String> autoApprove) {
        this.name = name;
        this.command = command;
        this.args = args != null ? new ArrayList<>(args) : new ArrayList<>();
        this.env = env != null ? new HashMap<>(env) : new HashMap<>();
        this.url = url;
        this.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
        this.autoApprove = autoApprove != null ? new ArrayList<>(autoApprove) : new ArrayList<>();
    }

    /**
     * 判断是否为 HTTP 模式（配置中包含 url 字段）
     */
    public boolean isHttpMode() {
        return url != null && !url.isEmpty();
    }

    public String getName() { return name; }
    public String getCommand() { return command; }
    public List<String> getArgs() { return Collections.unmodifiableList(args); }
    public Map<String, String> getEnv() { return Collections.unmodifiableMap(env); }
    public String getUrl() { return url; }
    public Map<String, String> getHeaders() { return Collections.unmodifiableMap(headers); }
    public List<String> getAutoApprove() { return Collections.unmodifiableList(autoApprove); }

    /**
     * 构建完整的进程启动命令（command + args），仅 stdio 模式使用
     */
    public List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        cmd.addAll(args);
        return cmd;
    }
}
