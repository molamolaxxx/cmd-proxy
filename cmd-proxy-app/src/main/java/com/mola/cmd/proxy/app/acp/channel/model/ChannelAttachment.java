package com.mola.cmd.proxy.app.acp.channel.model;

import java.util.Arrays;
import java.util.Objects;

/** Decrypted channel attachment waiting to be staged into the bound ACP workspace. */
public final class ChannelAttachment {
    public enum Origin { CURRENT, QUOTE }
    public enum Kind { IMAGE, FILE }

    private final Origin origin;
    private final Kind kind;
    private final String fileName;
    private final String mimeType;
    private final byte[] content;

    public ChannelAttachment(Origin origin, Kind kind, String fileName,
                             String mimeType, byte[] content) {
        this.origin = Objects.requireNonNull(origin, "origin");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.content = Arrays.copyOf(Objects.requireNonNull(content, "content"), content.length);
    }

    public Origin getOrigin() { return origin; }
    public Kind getKind() { return kind; }
    public String getFileName() { return fileName; }
    public String getMimeType() { return mimeType; }
    public byte[] getContent() { return Arrays.copyOf(content, content.length); }
    public int size() { return content.length; }
}
