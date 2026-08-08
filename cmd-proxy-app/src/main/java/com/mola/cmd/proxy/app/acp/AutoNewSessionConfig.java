package com.mola.cmd.proxy.app.acp;

/** Global idle-session rotation configuration. */
public class AutoNewSessionConfig {
    public static final int DEFAULT_CHECK_INTERVAL_MINUTES = 360;
    public static final int DEFAULT_IDLE_MINUTES = 180;

    private boolean enabled;
    private int checkIntervalMinutes = DEFAULT_CHECK_INTERVAL_MINUTES;
    private int idleMinutes = DEFAULT_IDLE_MINUTES;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getCheckIntervalMinutes() {
        return checkIntervalMinutes > 0 ? checkIntervalMinutes : DEFAULT_CHECK_INTERVAL_MINUTES;
    }

    public void setCheckIntervalMinutes(int checkIntervalMinutes) {
        this.checkIntervalMinutes = checkIntervalMinutes;
    }

    public int getIdleMinutes() {
        return idleMinutes > 0 ? idleMinutes : DEFAULT_IDLE_MINUTES;
    }

    public void setIdleMinutes(int idleMinutes) { this.idleMinutes = idleMinutes; }
}
