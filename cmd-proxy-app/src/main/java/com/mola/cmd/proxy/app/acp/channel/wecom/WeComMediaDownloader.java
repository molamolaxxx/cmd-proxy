package com.mola.cmd.proxy.app.acp.channel.wecom;

import com.google.gson.JsonObject;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelAttachment;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/** Downloads and decrypts short-lived WeCom media URLs using the official SDK algorithm. */
final class WeComMediaDownloader implements WeComInboundMessageParser.AttachmentDownloader {
    private static final long MAX_ENCRYPTED_BYTES = 51L * 1024L * 1024L;
    private final OkHttpClient client;

    WeComMediaDownloader(OkHttpClient client) {
        this.client = client;
    }

    @Override
    public ChannelAttachment download(JsonObject media, ChannelAttachment.Origin origin,
                                      ChannelAttachment.Kind kind, int index) throws IOException {
        String url = WeComProtocol.string(media, "url");
        String aesKey = WeComProtocol.string(media, "aeskey");
        if (url == null || !url.startsWith("https://") || aesKey == null || aesKey.trim().isEmpty()) {
            throw new IOException("WeCom media url or aeskey missing");
        }
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("media download HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("media response body missing");
            if (body.contentLength() > MAX_ENCRYPTED_BYTES) throw new IOException("media exceeds 50 MiB");
            byte[] encrypted = body.bytes();
            if (encrypted.length > MAX_ENCRYPTED_BYTES) throw new IOException("media exceeds 50 MiB");
            byte[] decrypted = decrypt(encrypted, aesKey);
            String mime = response.header("Content-Type");
            String fileName = contentDispositionFileName(response.header("Content-Disposition"));
            if (fileName == null || fileName.trim().isEmpty()) {
                fileName = (kind == ChannelAttachment.Kind.IMAGE ? "image-" : "file-")
                        + String.format(Locale.ROOT, "%02d", index) + extension(mime, kind);
            }
            return new ChannelAttachment(origin, kind, fileName, mime, decrypted);
        }
    }

    static byte[] decrypt(byte[] encrypted, String base64Key) throws IOException {
        if (encrypted == null || encrypted.length == 0 || encrypted.length % 16 != 0) {
            throw new IOException("invalid encrypted media length");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid media aeskey", e);
        }
        if (key.length != 32) throw new IOException("media aeskey must decode to 32 bytes");
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(Arrays.copyOf(key, 16)));
            byte[] plain = cipher.doFinal(encrypted);
            int padding = plain[plain.length - 1] & 0xff;
            if (padding < 1 || padding > 32 || padding > plain.length) {
                throw new IOException("invalid media PKCS#7 padding");
            }
            for (int i = plain.length - padding; i < plain.length; i++) {
                if ((plain[i] & 0xff) != padding) throw new IOException("invalid media padding bytes");
            }
            return Arrays.copyOf(plain, plain.length - padding);
        } catch (GeneralSecurityException e) {
            throw new IOException("media decryption failed", e);
        }
    }

    static String contentDispositionFileName(String header) {
        if (header == null) return null;
        String lower = header.toLowerCase(Locale.ROOT);
        int utf8 = lower.indexOf("filename*=utf-8''");
        if (utf8 >= 0) {
            String value = token(header.substring(utf8 + "filename*=utf-8''".length()));
            try { return URLDecoder.decode(value, StandardCharsets.UTF_8.name()); }
            catch (Exception ignored) { return value; }
        }
        int basic = lower.indexOf("filename=");
        if (basic < 0) return null;
        String value = token(header.substring(basic + "filename=".length())).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String token(String value) {
        int semicolon = value.indexOf(';');
        return (semicolon >= 0 ? value.substring(0, semicolon) : value).trim();
    }

    private static String extension(String mime, ChannelAttachment.Kind kind) {
        if (mime != null) {
            String normalized = mime.toLowerCase(Locale.ROOT);
            if (normalized.contains("png")) return ".png";
            if (normalized.contains("gif")) return ".gif";
            if (normalized.contains("webp")) return ".webp";
            if (normalized.contains("jpeg") || normalized.contains("jpg")) return ".jpg";
        }
        return kind == ChannelAttachment.Kind.IMAGE ? ".jpg" : ".bin";
    }
}
