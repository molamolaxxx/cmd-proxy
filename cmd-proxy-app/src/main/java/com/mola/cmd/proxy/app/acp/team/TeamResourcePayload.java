package com.mola.cmd.proxy.app.acp.team;

/** Bytes returned after a Team resource ID has been revalidated server-side. */
public final class TeamResourcePayload {
    private final String fileName;
    private final String contentType;
    private final byte[] bytes;

    public TeamResourcePayload(String fileName, String contentType, byte[] bytes) {
        this.fileName = fileName;
        this.contentType = contentType;
        this.bytes = bytes.clone();
    }

    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public byte[] getBytes() { return bytes.clone(); }
}
