package com.mola.cmd.proxy.app.acp.subagent.model;

/**
 * 子 Agent 引用配置，对应 acpConfig.json 中 robot.subAgents[] 的单个元素。
 * <p>
 * name 必须引用同一 robots 数组中已定义的 robot 名称。
 */
public class SubAgentRef {

    /** 引用的 robot 名称，必须在 robots 数组中存在 */
    private String name;

    /** 可选描述，覆盖 ability.md 的摘要 */
    private String description;

    public SubAgentRef() {}

    public SubAgentRef(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
