package com.securityexpert.nexus.ui2.worker.confirm;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded Gaia Embedded identity projection; raw diagnostic output is discarded. */
public final class SparkIdentityParser {
    private static final Pattern UNIT_MODEL = Pattern.compile("(?im)^\\s*Unit model\\s*:\\s*([A-Za-z0-9_.-]{1,64})\\s*$");
    private static final Pattern HW_VERSION = Pattern.compile("(?im)^\\s*HW version\\s*:\\s*([A-Za-z0-9_.-]{1,64})\\s*$");
    private static final Pattern IMAGE_VERSION = Pattern.compile("(?im)^\\s*Current image version\\s*:\\s*([A-Za-z0-9_.-]{1,64})\\s*$");
    private static final Pattern IMAGE_NAME = Pattern.compile("(?im)^\\s*Current image name\\s*:\\s*([A-Za-z0-9_.-]{1,128})\\s*$");
    private static final Pattern SOFTWARE_VERSION = Pattern.compile("(?im)^\\s*This is Check Point's .+ Appliance (R[0-9]+(?:\\.[0-9]+)+(?:\\.[0-9]+)?)\\s*-\\s*Build [0-9]+\\s*$");
    private static final Pattern PROMPT = Pattern.compile("^([A-Za-z0-9_.-]{1,64})>$");

    private SparkIdentityParser() {}

    private static Optional<String> field(Pattern pattern, String text) {
        if (text == null) return Optional.empty();
        Matcher match = pattern.matcher(text);
        return match.find() ? Optional.of(match.group(1)) : Optional.empty();
    }

    public static Optional<String> model(String diag) {
        return field(UNIT_MODEL, diag).or(() -> field(HW_VERSION, diag));
    }

    public static Optional<String> version(String softwareVersion, String diag) {
        return field(SOFTWARE_VERSION, softwareVersion)
                .or(() -> field(IMAGE_VERSION, diag))
                .or(() -> field(IMAGE_NAME, diag));
    }

    public static Optional<String> hostname(String prompt) {
        return field(PROMPT, prompt);
    }
}
