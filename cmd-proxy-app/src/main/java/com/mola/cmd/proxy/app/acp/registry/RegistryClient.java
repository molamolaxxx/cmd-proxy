package com.mola.cmd.proxy.app.acp.registry;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** 只发送注册控制请求，不承接业务消息；写操作不自动重放。 */
public final class RegistryClient {
    public JSONObject request(String center, String action, JSONObject body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(center + "/api/registry/" + action).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000); connection.setReadTimeout(8000);
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try {
            byte[] payload = body.toJSONString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
            int status = connection.getResponseCode();
            InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            JSONObject result = input == null ? new JSONObject() : JSON.parseObject(read(input));
            if (status < 200 || status >= 300) throw new IOException(result == null ? "中心请求失败: HTTP " + status : result.getString("error"));
            if (result == null) throw new IOException("中心响应为空");
            return result;
        } finally { connection.disconnect(); }
    }
    static String read(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = source.read(buffer)) >= 0) { if (output.size() + n > 65536) throw new IOException("注册请求或响应过大"); output.write(buffer, 0, n); }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
