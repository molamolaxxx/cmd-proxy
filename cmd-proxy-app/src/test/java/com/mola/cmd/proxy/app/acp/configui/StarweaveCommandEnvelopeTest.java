package com.mola.cmd.proxy.app.acp.configui;

import com.alibaba.fastjson.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class StarweaveCommandEnvelopeTest {
    @Test
    public void propagatesPromptAdmissionInsteadOfTreatingHttp200AsAccepted() {
        ConfigUiServer server = new ConfigUiServer(0, () -> { }, ignored -> { });
        JSONObject admission = new JSONObject(true);
        admission.put("accepted", false);
        admission.put("code", "CANCEL_FAILED");
        admission.put("message", "provider rejected cancellation");

        JSONObject envelope = server.starweaveCommandEnvelope("request-1", admission);

        assertFalse(envelope.getBooleanValue("accepted"));
        assertEquals("CANCEL_FAILED", envelope.getString("code"));
        assertEquals("provider rejected cancellation", envelope.getString("message"));
        assertSame(admission, envelope.get("data"));
    }
}
