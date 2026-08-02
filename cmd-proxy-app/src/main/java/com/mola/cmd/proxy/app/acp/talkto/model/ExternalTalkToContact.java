package com.mola.cmd.proxy.app.acp.talkto.model;

/** A discoverable non-robot TalkTo contact exposed with an opaque target. */
public final class ExternalTalkToContact {
    private final String target;
    private final String displayName;
    private final String remark;

    public ExternalTalkToContact(String target, String displayName, String remark) {
        this.target = target;
        this.displayName = displayName;
        this.remark = remark;
    }

    public String getTarget() { return target; }
    public String getDisplayName() { return displayName; }
    public String getRemark() { return remark; }
}
