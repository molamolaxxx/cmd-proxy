package com.mola.cmd.proxy.app.acp.talkto.model;

import java.util.*;

/** Server-owned causal metadata for one TalkTo cascade. */
public final class TalkToTrace {
    private static final String SAFE_ID_PATTERN = "[a-zA-Z0-9._-]{1,128}";
    private final String cascadeId;
    private final String messageId;
    private final String parentMessageId;
    private final int hopCount;
    private final long startedAt;
    private List<String> cascadeIds;

    public TalkToTrace(String cascadeId, String messageId, String parentMessageId,
                       int hopCount, long startedAt) {
        this.cascadeId = safeIdOrUuid(cascadeId, "cascadeId");
        this.messageId = safeIdOrUuid(messageId, "messageId");
        this.parentMessageId = safeOptionalId(parentMessageId, "parentMessageId");
        this.hopCount = Math.max(0, hopCount);
        this.startedAt = startedAt > 0L ? startedAt : System.currentTimeMillis();
    }

    public static TalkToTrace root() {
        String id = UUID.randomUUID().toString();
        return new TalkToTrace(id, UUID.randomUUID().toString(), null, 1,
                System.currentTimeMillis());
    }

    public TalkToTrace next() {
        return new TalkToTrace(cascadeId, UUID.randomUUID().toString(), messageId,
                hopCount + 1, startedAt).withCascadeIds(getCascadeIds());
    }

    /** A batch retains every causal budget; IDs are flattened and bounded, never recursive. */
    public List<String> getCascadeIds() {
        return cascadeIds == null ? Collections.singletonList(cascadeId) : cascadeIds;
    }

    public TalkToTrace withCascadeIds(Collection<String> values) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(cascadeId);
        if (values != null) for (String value : values) {
            if (value == null || !value.matches(SAFE_ID_PATTERN)) {
                throw new IllegalArgumentException("Invalid cascade ID");
            }
            ids.add(value);
        }
        if (ids.size() > 32) throw new IllegalArgumentException("Too many merged cascades");
        TalkToTrace copy = new TalkToTrace(cascadeId, messageId, parentMessageId, hopCount, startedAt);
        copy.cascadeIds = Collections.unmodifiableList(new ArrayList<>(ids));
        return copy;
    }

    public static TalkToTrace merge(Collection<TalkToTrace> traces) {
        TalkToTrace deepest = null;
        long earliest = Long.MAX_VALUE;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (TalkToTrace trace : traces) {
            if (deepest == null || trace.hopCount > deepest.hopCount) deepest = trace;
            earliest = Math.min(earliest, trace.startedAt);
            ids.addAll(trace.getCascadeIds());
        }
        if (deepest == null) throw new IllegalArgumentException("No traces to merge");
        return new TalkToTrace(deepest.cascadeId, deepest.messageId, deepest.parentMessageId,
                deepest.hopCount, earliest).withCascadeIds(ids);
    }

    public String getCascadeId() { return cascadeId; }
    public String getMessageId() { return messageId; }
    public String getParentMessageId() { return parentMessageId; }
    public int getHopCount() { return hopCount; }
    public long getStartedAt() { return startedAt; }

    private static String safeIdOrUuid(String value, String field) {
        String clean = clean(value);
        if (clean == null) return UUID.randomUUID().toString();
        if (!clean.matches(SAFE_ID_PATTERN)) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return clean;
    }

    private static String safeOptionalId(String value, String field) {
        String clean = clean(value);
        if (clean != null && !clean.matches(SAFE_ID_PATTERN)) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return clean;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String clean = value.trim();
        return clean.isEmpty() ? null : clean;
    }
}
