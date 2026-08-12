package com.mola.cmd.proxy.app.acp.team;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Decouples the Java ConfigUI compile phase from the Kotlin AcpProxy runtime. */
public final class TeamSharingStatusRegistry {
    private static volatile Supplier<List<Map<String, Object>>> supplier =
            Collections::emptyList;

    private TeamSharingStatusRegistry() { }

    public static void setSupplier(Supplier<List<Map<String, Object>>> next) {
        supplier = next == null ? Collections::emptyList : next;
    }

    public static List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> value = supplier.get();
        return value == null ? Collections.emptyList() : value;
    }

    public static void clear() { supplier = Collections::emptyList; }
}
