package io.github.themoddinginquisition.theinquisitor.util;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class Utils {
    public static final String URL_PREFIX = "url:";

    public static String getText(String textOrUrl) {
        if (textOrUrl.startsWith(URL_PREFIX)) {
            final var url = textOrUrl.substring(URL_PREFIX.length());
            try (final var is = new URL(url).openStream()) {
                return IOUtils.toString(is, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                return textOrUrl;
            }
        }
        return textOrUrl;
    }
}
