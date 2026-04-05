package com.mola.cmd.proxy.app.acp;

public class AcpRobotParam {
    private String name = "";
    private String signature = "";
    private String workDir = "";
    private String avatar = "";

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
}
