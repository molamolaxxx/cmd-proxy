package com.mola.cmd.proxy.app.acp.registry;

import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.registry.model.RegistryConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class RegistryEnvironmentAccessTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void passwordAndLoginSurviveRestartWithoutPersistingPlaintext() throws Exception {
        Path directory = temporary.newFolder().toPath();
        RegistryManager manager = new RegistryManager(directory, 12345, "local");
        try {
            JSONObject input = new JSONObject(); input.put("accessPassword", "测试密码 secret");
            JSONObject settings = manager.configure(input);
            assertTrue(settings.getBooleanValue("accessPasswordSet"));
            assertFalse(settings.containsKey("accessPasswordHash")); assertFalse(settings.containsKey("accessSessionKey"));
            assertFalse(new String(Files.readAllBytes(directory.resolve("config.json")), StandardCharsets.UTF_8).contains("测试密码 secret"));
            RegistryConfig config = new RegistryConfigStore(directory).load();
            assertTrue(RegistryEnvironmentAccess.passwordMatches(config, "测试密码 secret"));
            assertFalse(RegistryEnvironmentAccess.passwordMatches(config, "错误密码"));
            String ticket = RegistryEnvironmentAccess.ticket(config, 1000);
            RegistryConfig restored = new RegistryConfigStore(directory).load();
            assertEquals(1000 + RegistryEnvironmentAccess.SESSION_MILLIS, RegistryEnvironmentAccess.expiresAt(restored, ticket, 2000));
        } finally { manager.close(); }
    }

    @Test public void ticketsExpireAtSevenDaysAndRejectForgeryOtherEnvironmentsAndPasswordChanges() {
        RegistryConfig config = new RegistryConfig(); RegistryEnvironmentAccess.setPassword(config, "password");
        long now = 1000000, expiry = now + RegistryEnvironmentAccess.SESSION_MILLIS;
        String ticket = RegistryEnvironmentAccess.ticket(config, now);
        assertEquals(expiry, RegistryEnvironmentAccess.expiresAt(config, ticket, expiry - 1));
        assertEquals(0, RegistryEnvironmentAccess.expiresAt(config, ticket, expiry));
        assertEquals(0, RegistryEnvironmentAccess.expiresAt(config, (expiry + 1000) + ticket.substring(ticket.indexOf('.')), now));
        RegistryConfig other = new RegistryConfig(); RegistryEnvironmentAccess.setPassword(other, "password");
        assertEquals(0, RegistryEnvironmentAccess.expiresAt(other, ticket, now));
        RegistryEnvironmentAccess.setPassword(config, "new-password");
        assertEquals(0, RegistryEnvironmentAccess.expiresAt(config, ticket, now));
        assertFalse(RegistryEnvironmentAccess.passwordMatches(config, "password"));
        RegistryEnvironmentAccess.setPassword(config, "");
        assertEquals("", config.accessPasswordHash); assertEquals("", config.accessSessionKey);
    }
}
