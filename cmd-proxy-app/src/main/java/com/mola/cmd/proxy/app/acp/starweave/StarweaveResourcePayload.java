package com.mola.cmd.proxy.app.acp.starweave;

/** Immutable response returned only after server-side resourceId resolution. */
public final class StarweaveResourcePayload {
    private final String fileName;
    private final String contentType;
    private final byte[] bytes;

    StarweaveResourcePayload(String fileName, String contentType, byte[] bytes) {
        this.fileName = fileName;
        this.contentType = contentType;
        this.bytes = bytes.clone();
    }

    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public byte[] getBytes() { return bytes.clone(); }
}
