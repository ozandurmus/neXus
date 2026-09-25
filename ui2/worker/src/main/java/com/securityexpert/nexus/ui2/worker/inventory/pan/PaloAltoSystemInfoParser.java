package com.securityexpert.nexus.ui2.worker.inventory.pan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code show system info}: identity (serial, model, software version) and --
 * a parse-scope extension of the same read, PLATFORM_IDENTITY_FACTS_CONTRACT
 * §1 -- the platform facts an audit asks for: family, content / signature
 * versions, uptime. A tag the device did not send stays empty.
 */
public final class PaloAltoSystemInfoParser {

    private static final Pattern SW_VERSION_TAG = tag("sw-version");
    private static final Pattern SERIAL_TAG = tag("serial");
    private static final Pattern MODEL_TAG = tag("model");
    private static final Pattern HOSTNAME_TAG = tag("hostname");
    private static final Pattern FAMILY_TAG = tag("family");
    private static final Pattern UPTIME_TAG = tag("uptime");

    /** Content versions, by the name the screen shows them under, in display order. */
    private static final Map<String, Pattern> CONTENT_VERSION_TAGS = Map.of(
            "app", tag("app-version"),
            "threat", tag("threat-version"),
            "av", tag("av-version"),
            "wildfire", tag("wildfire-version"),
            "url", tag("url-filtering-version"));
    private static final String[] CONTENT_VERSION_ORDER = {"app", "threat", "av", "wildfire", "url"};

    private PaloAltoSystemInfoParser() {
    }

    public static SystemInfo parse(String xml) {
        String serial = firstMatch(SERIAL_TAG, xml).orElse("");
        Optional<String> swVersion = firstMatch(SW_VERSION_TAG, xml);
        Optional<String> model = firstMatch(MODEL_TAG, xml);
        Map<String, String> contentVersions = new LinkedHashMap<>();
        for (String name : CONTENT_VERSION_ORDER) {
            firstMatch(CONTENT_VERSION_TAGS.get(name), xml).ifPresent(v -> contentVersions.put(name, v));
        }
        return new SystemInfo(serial, swVersion, model, firstMatch(FAMILY_TAG, xml), contentVersions, firstMatch(HOSTNAME_TAG, xml),
                firstMatch(UPTIME_TAG, xml));
    }

    private static Pattern tag(String name) {
        return Pattern.compile("(?is)<" + name + ">\\s*([^<]+?)\\s*</" + name + ">");
    }

    private static Optional<String> firstMatch(Pattern pattern, String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public record SystemInfo(String serial, Optional<String> swVersion, Optional<String> model,
            Optional<String> family, Map<String, String> contentVersions, Optional<String> hostname, Optional<String> uptime) {

        public SystemInfo {
            contentVersions = contentVersions == null ? Map.of() : Map.copyOf(contentVersions);
            hostname = hostname == null ? Optional.empty() : hostname;
        }

        public SystemInfo(String serial, Optional<String> swVersion, Optional<String> model) {
            this(serial, swVersion, model, Optional.empty(), Map.of(), Optional.empty(), Optional.empty());
        }
    }
}
