package com.mola.cmd.proxy.app.acp.gateway;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.function.Supplier;

/** Java-owned ConfigUI bridge; avoids Java compilation depending on Kotlin AcpProxy. */
public final class AgentGatewayAdminBridge {
    private static volatile Supplier<JSONArray> targets = JSONArray::new;
    private static volatile Supplier<JSONObject> status = () -> {
        JSONObject value = new JSONObject(true); value.put("status", "STOPPED"); return value;
    };

    private AgentGatewayAdminBridge() { }

    public static void install(Supplier<JSONArray> targetSupplier,
                               Supplier<JSONObject> statusSupplier) {
        targets = targetSupplier == null ? JSONArray::new : targetSupplier;
        status = statusSupplier == null ? status : statusSupplier;
    }

    public static JSONArray targets() { return targets.get(); }
    public static JSONObject status() { return status.get(); }
}
