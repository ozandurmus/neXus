package com.securityexpert.nexus.ui2.worker.inventory.pan;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PaloAltoSystemInfoParser {

    private static final Pattern SW_VERSION_TAG = Pattern.compile("(?is)<sw-version>\\s*([^<]+?)\\s*</sw-version>");
    private static final Pattern SERIAL_TAG = Pattern.compile("(?is)<serial>\\s*([^<]+?)\\s*</serial>");
    private static final Pattern MODEL_TAG = Pattern.compile("(?is)<model>\\s*([^<]+?)\\s*</model>");

    private PaloAltoSystemInfoParser() {
    }

    public static SystemInfo parse(String xml) {
        String serial = firstMatch(SERIAL_TAG, xml).orElse("");
        Optional<String> swVersion = firstMatch(SW_VERSION_TAG, xml);
        Optional<String> model = firstMatch(MODEL_TAG, xml);
        return new SystemInfo(serial, swVersion, model);
    }

    private static Optional<String> firstMatch(Pattern pattern, String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public record SystemInfo(String serial, Optional<String> swVersion, Optional<String> model) {}
}
