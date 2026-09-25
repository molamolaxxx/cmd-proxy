package com.mola.cmd.proxy.app.acp;

/** Per-client automatic sleep configuration. */
public class AutoSleepConfig {
    public static final int DEFAULT_IDLE_MINUTES = 30;

    private boolean enabled;
    private int idleMinutes = DEFAULT_IDLE_MINUTES;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getIdleMinutes() {
        return idleMinutes > 0 ? idleMinutes : DEFAULT_IDLE_MINUTES;
    }

    public void setIdleMinutes(int idleMinutes) { this.idleMinutes = idleMinutes; }
}
