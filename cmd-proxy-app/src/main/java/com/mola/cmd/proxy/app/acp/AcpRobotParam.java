package com.mola.cmd.proxy.app.acp;

import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;

public class AcpRobotParam {
    private String name = "";
    private String signature = "";
    private String workDir = "";
    private String avatar = "";
    private MemoryConfig memory;
    private String agentProvider = "KIRO_CLI";

    public AcpRobotParam() {
    }

    public AcpRobotParam(String name, String signature, String workDir, String avatar) {
        this.name = name;
        this.signature = signature;
        this.workDir = workDir;
        this.avatar = avatar;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getWorkDir() {
        return workDir;
    }

    public void setWorkDir(String workDir) {
        this.workDir = workDir;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public MemoryConfig getMemory() {
        return memory;
    }

    public void setMemory(MemoryConfig memory) {
        this.memory = memory;
    }

    /**
     * 该 robot 是否开启了记忆。
     */
    public boolean isMemoryEnabled() {
        return memory != null && memory.isEnabled();
    }

    public String getAgentProvider() {
        return agentProvider;
    }

    public void setAgentProvider(String agentProvider) {
        this.agentProvider = agentProvider;
    }
}
