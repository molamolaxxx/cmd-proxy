package com.mola.cmd.proxy.app.acp;

import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig;
import com.mola.cmd.proxy.app.acp.subagent.model.SubAgentRef;
import com.mola.cmd.proxy.app.acp.talkto.model.ContactRef;

import java.util.List;

public class AcpRobotParam {
    private String name = "";
    private String signature = "";
    private String workDir = "";
    private String avatar = "";
    private boolean enabled = true;
    private MemoryConfig memory;
    private String agentProvider = "KIRO_CLI";
    private boolean abilityAutoRefresh = true;
    private List<SubAgentRef> subAgents;
    private boolean onlySubAgent;
    private boolean scheduleEnabled = false;
    private List<ContactRef> contacts;

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
        if (avatar != null && !avatar.isEmpty()) {
            return avatar;
        }
        if ("OPENCODE".equalsIgnoreCase(agentProvider)) {
            return "img/opencode.png";
        }
        return "img/kiro.png";
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public boolean isAbilityAutoRefresh() {
        return abilityAutoRefresh;
    }

    public void setAbilityAutoRefresh(boolean abilityAutoRefresh) {
        this.abilityAutoRefresh = abilityAutoRefresh;
    }

    public String getAgentProvider() {
        return agentProvider;
    }

    public void setAgentProvider(String agentProvider) {
        this.agentProvider = agentProvider;
    }

    public List<SubAgentRef> getSubAgents() {
        return subAgents;
    }

    public void setSubAgents(List<SubAgentRef> subAgents) {
        this.subAgents = subAgents;
    }

    /**
     * 该 robot 是否配置了子 Agent。
     */
    public boolean hasSubAgents() {
        return subAgents != null && !subAgents.isEmpty();
    }

    public boolean isOnlySubAgent() {
        return onlySubAgent;
    }

    public void setOnlySubAgent(boolean onlySubAgent) {
        this.onlySubAgent = onlySubAgent;
    }

    public boolean isScheduleEnabled() {
        return scheduleEnabled;
    }

    public void setScheduleEnabled(boolean scheduleEnabled) {
        this.scheduleEnabled = scheduleEnabled;
    }

    public List<ContactRef> getContacts() {
        return contacts;
    }

    public void setContacts(List<ContactRef> contacts) {
        this.contacts = contacts;
    }

    /**
     * 该 robot 是否配置了通讯录。
     */
    public boolean hasContacts() {
        return contacts != null && !contacts.isEmpty();
    }
}
