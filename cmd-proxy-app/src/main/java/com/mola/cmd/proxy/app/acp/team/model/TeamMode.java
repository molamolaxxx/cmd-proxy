package com.mola.cmd.proxy.app.acp.team.model;

/** Stable wire values for Fast Team collaboration topology. */
public enum TeamMode {
    NORMAL,
    CAPTAIN;

    /** Historical definitions and requests omitted the field and remain NORMAL. */
    public static TeamMode fromWire(String value) {
        if (value == null || value.trim().isEmpty()) return NORMAL;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported Team mode: " + value);
        }
    }
}
