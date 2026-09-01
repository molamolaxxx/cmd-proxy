package com.mola.cmd.proxy.app.acp.acpclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AcpClientMemoryAccessInputTest {

    @Test
    public void collectsStringLeavesFromStructuredAndBashToolInputs() {
        JsonObject input = new JsonObject();
        input.addProperty("cmd", "sed -n '1,80p' /tmp/memory.md");
        input.addProperty("timeout", 1000);
        JsonArray operations = new JsonArray();
        JsonObject read = new JsonObject();
        read.addProperty("path", "/tmp/other-memory.md");
        read.addProperty("recursive", false);
        operations.add(read);
        input.add("operations", operations);

        List<String> strings = new ArrayList<>();
        AcpClient.collectToolInputStrings(input, strings);

        assertEquals(2, strings.size());
        assertTrue(strings.contains("sed -n '1,80p' /tmp/memory.md"));
        assertTrue(strings.contains("/tmp/other-memory.md"));
    }
}
