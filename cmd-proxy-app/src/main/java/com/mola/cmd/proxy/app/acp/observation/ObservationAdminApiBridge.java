package com.mola.cmd.proxy.app.acp.observation;

public final class ObservationAdminApiBridge {
    private static volatile ObservationManager manager;

    private ObservationAdminApiBridge() {}

    public static void install(ObservationManager value) {
        manager = value;
    }

    public static void clear(ObservationManager value) {
        if (manager == value) manager = null;
    }

    public static ObservationManager current() {
        ObservationManager value = manager;
        if (value == null) throw new IllegalStateException("观测服务未启动");
        return value;
    }
}
