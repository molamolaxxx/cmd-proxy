package com.mola.cmd.proxy.app.mcpclient;

import java.util.*;

/**
 * MCP Server 的连接配置，对应 mcp.json 中的一个 server 配置项。
 */
public class McpClientConfig {

    private final String name;
    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    private final List<String> autoApprove;

    public McpClientConfig(String name, String command, List<String> args, Map<String, String> env, List<String> autoApprove) {
        this.name = name;
        this.command = command;
        this.args = args != null ? new ArrayList<>(args) : new ArrayList<>();
        this.env = env != null ? new HashMap<>(env) : new HashMap<>();
        this.autoApprove = autoApprove != null ? new ArrayList<>(autoApprove) : new ArrayList<>();
    }

    public String getCommand() {
        return command;
    }

    public List<String> getArgs() {
        return Collections.unmodifiableList(args);
    }

    public Map<String, String> getEnv() {
        return Collections.unmodifiableMap(env);
    }

    public String getName() {
        return name;
    }

    public List<String> getAutoApprove() {
        return Collections.unmodifiableList(autoApprove);
    }

    /**
     * 构建完整的进程启动命令（command + args）
     */
    public List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        cmd.addAll(args);
        return cmd;
    }
}
