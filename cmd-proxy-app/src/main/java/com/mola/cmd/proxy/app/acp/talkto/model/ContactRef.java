package com.mola.cmd.proxy.app.acp.talkto.model;

/**
 * 通讯录条目，对应 acpConfig.json 中 robot.contacts[] 的单个元素。
 * 支持本地联系人和跨 chatter 远程联系人。
 */
public class ContactRef {

    /** 目标 robot 名称 */
    private String name;

    /** 备注说明，帮助 LLM 判断何时联系。远程联系人时必填。 */
    private String remark;

    /** 目标 chatter ID，远程联系人时必填 */
    private String chatterId;

    /** 是否为远程联系人（跨 chatter） */
    private boolean isRemote;

    public ContactRef() {}

    public ContactRef(String name, String remark) {
        this.name = name;
        this.remark = remark;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public String getChatterId() { return chatterId; }
    public void setChatterId(String chatterId) { this.chatterId = chatterId; }

    public boolean isRemote() { return isRemote; }
    public void setRemote(boolean remote) { isRemote = remote; }
}
