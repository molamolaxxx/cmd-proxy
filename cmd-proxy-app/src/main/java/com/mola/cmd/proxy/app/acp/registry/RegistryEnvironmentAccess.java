package com.mola.cmd.proxy.app.acp.registry;

import com.mola.cmd.proxy.app.acp.registry.model.RegistryConfig;
import com.sun.net.httpserver.HttpExchange;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

/** 环境访问密码与注册协议分离；浏览器只保存有期限的登录票据，不保存密码。 */
public final class RegistryEnvironmentAccess {
    public static final long SESSION_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private RegistryEnvironmentAccess() { }
    public static void setPassword(RegistryConfig config, String password) {
        if (password.length() > 128) throw new IllegalArgumentException("密码过长，请控制在 128 个字符以内");
        config.accessPasswordHash = password.isEmpty() ? "" : hash(password, random(16));
        config.accessSessionKey = password.isEmpty() ? "" : random(32);
    }
    public static boolean passwordMatches(RegistryConfig config, String password) {
        if (config.accessPasswordHash.isEmpty() || password == null || password.length() > 128) return false;
        String salt = config.accessPasswordHash.split(":", 2)[0];
        return equal(config.accessPasswordHash, hash(password, salt));
    }
    private static String hash(String password, String salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), decode(salt), 120000, 256);
        try { return salt + ":" + encode(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded()); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("无法保存环境密码", e); }
        finally { spec.clearPassword(); }
    }
    public static String cookieName(RegistryConfig config) { return "sw_env_" + config.nodeId.replace("-", "_"); }
    public static String ticket(RegistryConfig config, long now) {
        String value = (now + SESSION_MILLIS) + "." + random(16);
        return value + "." + sign(config, value);
    }
    public static long expiresAt(RegistryConfig config, String ticket, long now) {
        if (config.accessPasswordHash.isEmpty() || ticket == null || ticket.length() > 256) return 0;
        try {
            String[] parts = ticket.split("\\.", -1);
            if (parts.length != 3) return 0;
            long expires = Long.parseLong(parts[0]);
            if (expires <= now || expires - now > SESSION_MILLIS) return 0;
            return equal(parts[2], sign(config, parts[0] + "." + parts[1])) ? expires : 0;
        } catch (RuntimeException e) { return 0; }
    }
    public static long expiresAt(RegistryConfig config, HttpExchange exchange) {
        String name = cookieName(config);
        java.util.List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies != null) for (String header : cookies) for (String pair : header.split(";")) {
            String[] entry = pair.trim().split("=", 2);
            if (entry.length == 2 && name.equals(entry[0])) return expiresAt(config, entry[1], System.currentTimeMillis());
        }
        return 0;
    }
    public static boolean sameOrigin(HttpExchange exchange) {
        if ("cross-site".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Sec-Fetch-Site"))) return false;
        String origin = exchange.getRequestHeaders().getFirst("Origin"), host = exchange.getRequestHeaders().getFirst("Host");
        if (origin == null) return true;
        try {
            java.net.URI uri = java.net.URI.create(origin);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && host != null && host.equalsIgnoreCase(uri.getRawAuthority());
        } catch (RuntimeException e) { return false; }
    }
    private static String sign(RegistryConfig config, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(decode(config.accessSessionKey), "HmacSHA256"));
            return encode(mac.doFinal((config.nodeId + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException("无法验证环境登录状态", e); }
    }
    private static boolean equal(String a, String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
    private static String random(int size) { byte[] bytes = new byte[size]; RANDOM.nextBytes(bytes); return encode(bytes); }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static byte[] decode(String value) { return Base64.getUrlDecoder().decode(value); }
}
