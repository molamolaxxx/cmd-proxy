package com.mola.cmd.proxy.app.acp.configui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigUiTestResources {
    private static final Pattern LOCAL_RESOURCE = Pattern.compile(
            "(?:href|src)=\"/(assets/(?:css|js)/[^\"]+)\"");

    private ConfigUiTestResources() {
    }

    public static String loadBundle() throws IOException {
        String html = load("configui/index.html");
        StringBuilder bundle = new StringBuilder(html);
        Matcher matcher = LOCAL_RESOURCE.matcher(html);
        while (matcher.find()) {
            bundle.append('\n').append(load("configui/" + matcher.group(1)));
        }
        return bundle.toString();
    }

    public static String load(String resourcePath) throws IOException {
        ClassLoader loader = ConfigUiTestResources.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException(resourcePath + " missing");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
