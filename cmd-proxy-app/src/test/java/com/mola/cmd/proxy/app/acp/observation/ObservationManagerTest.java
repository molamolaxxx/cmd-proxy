package com.mola.cmd.proxy.app.acp.observation;

import static org.junit.Assert.*;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

public class ObservationManagerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final ScheduleOwnerKey owner = ScheduleOwnerKey.main("assistant");

    private JsonObject input() {
        JsonObject value = new JsonObject();
        value.addProperty("name", "Jira");
        value.addProperty("frequency", "60h");
        value.addProperty(
                "script", "module.exports=()=>require('fs').readFileSync('value.txt','utf8')");
        return value;
    }

    private void write(Path workspace, String value) throws Exception {
        Files.write(workspace.resolve("value.txt"), value.getBytes(StandardCharsets.UTF_8));
    }

    private void observe(ObservationManager manager, String id) throws Exception {
        long previous =
                manager.get(id, null).has("lastRunAt")
                        ? manager.get(id, null).get("lastRunAt").getAsLong()
                        : 0;
        JsonObject update = new JsonObject();
        update.addProperty("name", "Jira");
        manager.update(id, null, update);
        manager.tick();
        await(
                () ->
                        manager.get(id, null).has("lastRunAt")
                                && manager.get(id, null).get("lastRunAt").getAsLong() > previous);
    }

    private static void await(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue("等待观测状态超时", ready.getAsBoolean());
    }

    private JsonArray events(ObservationManager manager, String id) {
        return manager.events(null, id, null, null, 1, 100).getAsJsonArray("items");
    }

    @Test
    public void baselinesChangesFailuresTestingAndRestartRemainConsistent() throws Exception {
        Path directory = temporary.newFolder("store").toPath(),
                workspace = temporary.newFolder("workspace").toPath();
        write(workspace, "A");
        ObservationManager manager = new ObservationManager(directory);
        AtomicReference<String> delivered = new AtomicReference<>();
        try {
            manager.register(
                    this,
                    owner,
                    workspace.toString(),
                    () -> true,
                    prompt -> {
                        delivered.set(prompt);
                        return true;
                    });
            String id = manager.create(owner.getPersistencePath(), input()).get("id").getAsString();
            observe(manager, id);
            assertEquals(0, events(manager, id).size());
            observe(manager, id);
            assertEquals(0, events(manager, id).size());
            write(workspace, "B");
            JsonObject test = new JsonObject();
            test.addProperty("channel_id", id);
            assertEquals(
                    "B",
                    manager.test(owner.getPersistencePath(), test).get("result").getAsString());
            assertEquals("A", manager.get(id, null).get("baseline").getAsString());
            assertEquals(0, events(manager, id).size());
            Files.delete(workspace.resolve("value.txt"));
            observe(manager, id);
            assertTrue(manager.get(id, null).has("lastError"));
            assertEquals("A", manager.get(id, null).get("baseline").getAsString());
            write(workspace, "B");
            observe(manager, id);
            assertEquals(1, events(manager, id).size());
            assertFalse(manager.get(id, null).has("lastError"));
            manager.tick();
            await(
                    () ->
                            "DELIVERED"
                                    .equals(
                                            events(manager, id)
                                                    .get(0)
                                                    .getAsJsonObject()
                                                    .get("status")
                                                    .getAsString()));
            assertTrue(delivered.get().contains("变化前的观测结果：\nA"));
            assertTrue(delivered.get().contains("变化后的观测结果：\nB"));
            manager.close();
            try (ObservationManager restored = new ObservationManager(directory)) {
                restored.register(this, owner, workspace.toString(), () -> true, prompt -> false);
                assertEquals("B", restored.get(id, null).get("baseline").getAsString());
                assertEquals(1, events(restored, id).size());
                JsonObject edit = new JsonObject();
                edit.addProperty("script", "module.exports=()=> 'different'");
                restored.update(id, null, edit);
                assertFalse(restored.get(id, null).has("baseline"));
                observe(restored, id);
                assertEquals(1, events(restored, id).size());
            }
        } finally {
            manager.close();
        }
    }

    @Test
    public void disabledCapabilityRebaselinesAndPreservesHistoryAfterDelete() throws Exception {
        Path directory = temporary.newFolder().toPath(), workspace = temporary.newFolder().toPath();
        write(workspace, "A");
        AtomicBoolean enabled = new AtomicBoolean(true);
        try (ObservationManager manager = new ObservationManager(directory)) {
            manager.register(this, owner, workspace.toString(), enabled::get, prompt -> false);
            String id = manager.create(owner.getPersistencePath(), input()).get("id").getAsString();
            observe(manager, id);
            write(workspace, "B");
            observe(manager, id);
            manager.tick();
            await(
                    () ->
                            events(manager, id).get(0).getAsJsonObject().get("attempts").getAsInt()
                                    > 0);
            assertEquals(
                    "PENDING",
                    events(manager, id).get(0).getAsJsonObject().get("status").getAsString());
            enabled.set(false);
            manager.tick();
            assertEquals("DISABLED", manager.get(id, null).get("status").getAsString());
            assertEquals(
                    "FAILED",
                    events(manager, id).get(0).getAsJsonObject().get("status").getAsString());
            write(workspace, "C");
            enabled.set(true);
            manager.tick();
            await(
                    () ->
                            manager.get(id, null).has("baseline")
                                    && "C"
                                            .equals(
                                                    manager.get(id, null)
                                                            .get("baseline")
                                                            .getAsString()));
            assertEquals(1, events(manager, id).size());
            String eventId = events(manager, id).get(0).getAsJsonObject().get("id").getAsString();
            manager.retry(id, eventId);
            manager.delete(id, null);
            assertEquals(
                    "FAILED",
                    events(manager, id).get(0).getAsJsonObject().get("status").getAsString());
            assertTrue(manager.catalog().toString().contains("\"deleted\":true"));
            assertEquals(0, manager.list(null, null, null, 1, 10).get("total").getAsInt());
        }
    }

    @Test
    public void toolsIsolateOwnersAndInflightScriptEdits() throws Exception {
        Path workspace = temporary.newFolder().toPath();
        write(workspace, "A");
        ScheduleOwnerKey other = ScheduleOwnerKey.team("user", "team", "member", "assistant");
        try (ObservationManager manager = new ObservationManager(temporary.newFolder().toPath())) {
            manager.register(this, owner, workspace.toString(), () -> true, prompt -> false);
            manager.register(
                    new Object(), other, workspace.toString(), () -> true, prompt -> false);
            String id = manager.create(owner.getPersistencePath(), input()).get("id").getAsString();
            JsonObject args = new JsonObject();
            args.addProperty("action", "get");
            args.addProperty("channel_id", id);
            try {
                manager.executeTool(
                        "manage_observation_channels", args, other.getPersistencePath());
                fail("跨 owner 应拒绝");
            } catch (IllegalArgumentException expected) {
            }
            JsonObject slow = new JsonObject();
            slow.addProperty(
                    "script",
                    "module.exports=async()=>{await new Promise(r=>setTimeout(r,250));return"
                            + " 'old'}");
            manager.update(id, null, slow);
            manager.tick();
            JsonObject replacement = new JsonObject();
            replacement.addProperty("script", "module.exports=()=> 'new'");
            manager.update(id, null, replacement);
            await(() -> !manager.get(id, null).get("running").getAsBoolean());
            assertFalse(manager.get(id, null).has("baseline"));
            observe(manager, id);
            assertEquals("new", manager.get(id, null).get("baseline").getAsString());
            assertEquals(0, events(manager, id).size());
            assertEquals(
                    0,
                    manager.events(other.getPersistencePath(), id, null, null, 1, 10)
                            .get("total")
                            .getAsInt());
        }
    }

    @Test
    public void configuredAgentObservesWithoutSessionAndRetriesDeliveryFailure() throws Exception {
        Path workspace = temporary.newFolder().toPath();
        write(workspace, "A");
        AtomicBoolean available = new AtomicBoolean(false);
        try (ObservationManager manager = new ObservationManager(temporary.newFolder().toPath())) {
            manager.registerConfigured(
                    owner,
                    workspace.toString(),
                    () -> true,
                    prompt -> {
                        if (!available.get()) throw new java.io.IOException("启动失败");
                        return true;
                    });
            assertEquals(1, manager.owners().getAsJsonArray("items").size());
            String id = manager.create(owner.getPersistencePath(), input()).get("id").getAsString();
            observe(manager, id);
            write(workspace, "B");
            observe(manager, id);
            manager.tick();
            await(
                    () ->
                            "FAILED"
                                    .equals(
                                            events(manager, id)
                                                    .get(0)
                                                    .getAsJsonObject()
                                                    .get("status")
                                                    .getAsString()));
            JsonObject event = events(manager, id).get(0).getAsJsonObject();
            assertEquals(1, event.get("attempts").getAsInt());
            assertTrue(event.get("error").getAsString().contains("启动失败"));
            available.set(true);
            manager.retry(id, event.get("id").getAsString());
            manager.tick();
            await(
                    () ->
                            "DELIVERED"
                                    .equals(
                                            events(manager, id)
                                                    .get(0)
                                                    .getAsJsonObject()
                                                    .get("status")
                                                    .getAsString()));
            assertEquals(event.get("id"), events(manager, id).get(0).getAsJsonObject().get("id"));
        }
    }

    @Test
    public void validatesFrequencyAndPaginatesEmptyResults() throws Exception {
        assertEquals(30000, ObservationManager.frequency("30s"));
        assertEquals(600000, ObservationManager.frequency("10min"));
        assertEquals(216000000, ObservationManager.frequency("60h"));
        for (String value : new String[] {"0s", "-1s", "1.5s", "30", "999999999999999999999h"})
            try {
                ObservationManager.frequency(value);
                fail(value);
            } catch (IllegalArgumentException expected) {
            }
        try (ObservationManager manager = new ObservationManager(temporary.newFolder().toPath())) {
            JsonObject page = manager.events(null, null, null, null, 1, 10);
            assertEquals(0, page.get("total").getAsInt());
            assertEquals(1, page.get("totalPages").getAsInt());
        }
    }
}
