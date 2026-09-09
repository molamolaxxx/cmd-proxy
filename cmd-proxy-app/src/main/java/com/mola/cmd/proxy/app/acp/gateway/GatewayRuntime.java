package com.mola.cmd.proxy.app.acp.gateway;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.gateway.model.GatewayTarget;

import java.util.List;
import java.util.Map;

/** Surface-neutral boundary implemented by the ACP composition root. */
public interface GatewayRuntime {
    JSONArray targets();
    JSONObject status(GatewayTarget target);
    JSONObject send(GatewayTarget target, String message,
                    List<Map<String, String>> files, String busyPolicy);
    JSONObject cancel(GatewayTarget target);
    JSONObject newSession(GatewayTarget target) throws Exception;
}
