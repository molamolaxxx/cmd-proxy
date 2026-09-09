package com.mola.cmd.proxy.app.acp.gateway;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.gateway.model.AgentGatewayConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Downloads URL attachments with redirect, address, size and digest validation. */
public final class GatewayAttachmentDownloader {
    private static final int MAX_REDIRECTS = 5;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .build();

    public List<Map<String, String>> download(AgentGatewayConfig gateway, JSONArray attachments) {
        if (attachments == null || attachments.isEmpty()) return java.util.Collections.emptyList();
        AgentGatewayConfig.Limits limits = gateway.getLimits();
        if (attachments.size() > limits.getMaxFileCount()) {
            throw new GatewayException(413, "ATTACHMENT_TOO_LARGE",
                    "Too many attachments", false);
        }
        List<Map<String, String>> result = new ArrayList<>();
        long total = 0L;
        for (int i = 0; i < attachments.size(); i++) {
            JSONObject attachment = attachments.getJSONObject(i);
            if (attachment == null) throw invalid("attachments[" + i + "] must be an object");
            String name = cleanName(attachment.getString("name"));
            long remaining = limits.getMaxTotalFileBytes() - total;
            if (remaining <= 0L) {
                throw new GatewayException(413, "ATTACHMENT_TOO_LARGE",
                        "Total attachment size exceeds the configured limit", false);
            }
            byte[] bytes = fetch(gateway, attachment.getString("url"),
                    Math.min(limits.getMaxFileBytes(), remaining), 0);
            total += bytes.length;
            if (total > limits.getMaxTotalFileBytes()) {
                throw new GatewayException(413, "ATTACHMENT_TOO_LARGE",
                        "Total attachment size exceeds the configured limit", false);
            }
            Long expectedSize = attachment.getLong("size");
            if (expectedSize != null && expectedSize.longValue() != bytes.length) {
                throw new GatewayException(422, "ATTACHMENT_DOWNLOAD_FAILED",
                        "Attachment size does not match: " + name, true);
            }
            String expectedSha = text(attachment.getString("sha256"));
            if (!expectedSha.isEmpty() && !constantTimeEquals(expectedSha, sha256(bytes))) {
                throw new GatewayException(422, "ATTACHMENT_DOWNLOAD_FAILED",
                        "Attachment SHA-256 does not match: " + name, true);
            }
            attachment.put("size", bytes.length);
            attachment.put("sha256", sha256(bytes));
            Map<String, String> file = new LinkedHashMap<>();
            file.put(name, Base64.getEncoder().encodeToString(bytes));
            result.add(file);
        }
        return result;
    }

    private byte[] fetch(AgentGatewayConfig gateway, String rawUrl, long maxBytes, int redirects) {
        if (redirects > MAX_REDIRECTS) throw forbidden("Too many attachment redirects");
        if (rawUrl == null || rawUrl.length() > 4096) {
            throw forbidden("Attachment URL is missing or too long");
        }
        URI uri;
        try { uri = URI.create(text(rawUrl)); }
        catch (RuntimeException e) { throw forbidden("Attachment URL is invalid"); }
        validateUrl(gateway, uri);
        Request request = new Request.Builder().url(uri.toString()).get().build();
        try (Response response = clientFor(gateway).newCall(request).execute()) {
            if (response.isRedirect()) {
                String location = response.header("Location");
                if (location == null) throw failed("Attachment redirect is missing Location");
                return fetch(gateway, uri.resolve(location).toString(), maxBytes, redirects + 1);
            }
            if (!response.isSuccessful()) {
                throw failed("Attachment server returned HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) throw failed("Attachment response has no body");
            if (body.contentLength() > maxBytes) {
                throw new GatewayException(413, "ATTACHMENT_TOO_LARGE",
                        "Attachment exceeds the configured size limit", false);
            }
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long count = 0L;
            try (java.io.InputStream input = body.byteStream()) {
                int length;
                while ((length = input.read(buffer)) >= 0) {
                    count += length;
                    if (count > maxBytes) {
                        throw new GatewayException(413, "ATTACHMENT_TOO_LARGE",
                                "Attachment exceeds the configured size limit", false);
                    }
                    output.write(buffer, 0, length);
                }
            }
            return output.toByteArray();
        } catch (GatewayException e) { throw e; }
        catch (Exception e) { throw failed("Attachment download failed: " + e.getMessage()); }
    }

    private OkHttpClient clientFor(AgentGatewayConfig gateway) {
        if (gateway.getFilePolicy().isAllowPrivateAddress()) return client;
        return client.newBuilder().dns(hostname -> {
            InetAddress[] resolved;
            try { resolved = InetAddress.getAllByName(hostname); }
            catch (Exception failure) {
                UnknownHostException error = new UnknownHostException(hostname);
                error.initCause(failure);
                throw error;
            }
            for (InetAddress address : resolved) {
                if (isPrivate(address)) {
                    throw new UnknownHostException(
                            "Attachment address is private or local");
                }
            }
            return Arrays.asList(resolved);
        }).build();
    }

    private void validateUrl(AgentGatewayConfig gateway, URI uri) {
        String scheme = text(uri.getScheme()).toLowerCase(Locale.ROOT);
        boolean schemeAllowed = false;
        for (String allowed : gateway.getFilePolicy().getAllowedSchemes()) {
            if (scheme.equals(text(allowed).toLowerCase(Locale.ROOT))) schemeAllowed = true;
        }
        if (!schemeAllowed || uri.getHost() == null || uri.getUserInfo() != null) {
            throw forbidden("Attachment URL scheme or authority is not allowed");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        List<String> allowedHosts = gateway.getFilePolicy().getAllowedHosts();
        boolean hostAllowed = false;
        for (String allowedValue : allowedHosts) {
            String allowed = text(allowedValue).toLowerCase(Locale.ROOT);
            if (host.equals(allowed) || (allowed.startsWith("*.")
                    && host.endsWith(allowed.substring(1))
                    && host.length() > allowed.length() - 1)) hostAllowed = true;
        }
        if (!hostAllowed) throw forbidden("Attachment host is not allowed");
        if (!gateway.getFilePolicy().isAllowPrivateAddress()) {
            try {
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    if (isPrivate(address)) throw forbidden("Attachment address is private or local");
                }
            } catch (GatewayException e) { throw e; }
            catch (Exception e) { throw failed("Attachment host cannot be resolved"); }
        }
    }

    static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff, second = bytes[1] & 0xff;
            return first == 0 || first == 10 || first == 127
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || first >= 224;
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xff;
            return (first & 0xfe) == 0xfc || (first == 0xfe && (bytes[1] & 0xc0) == 0x80);
        }
        return true;
    }

    private static String cleanName(String value) {
        String name = text(value);
        if (name.isEmpty() || name.length() > 255 || name.contains("/") || name.contains("\\")
                || ".".equals(name) || "..".equals(name)) throw invalid("Attachment name is invalid");
        return name;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                right.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static GatewayException invalid(String message) {
        return new GatewayException(400, "INVALID_ARGUMENT", message, false);
    }
    private static GatewayException forbidden(String message) {
        return new GatewayException(422, "ATTACHMENT_URL_FORBIDDEN", message, false);
    }
    private static GatewayException failed(String message) {
        return new GatewayException(422, "ATTACHMENT_DOWNLOAD_FAILED", message, true);
    }
    private static String text(String value) { return value == null ? "" : value.trim(); }
}
