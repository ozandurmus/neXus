package com.securityexpert.nexus.ui2.worker.inventory.pan;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PaloAltoHaStateParser {

    public static final String STANDALONE = "STANDALONE";
    public static final String UNKNOWN = "UNKNOWN";

    public record HaState(String role, Optional<String> clusterMode, Optional<String> localSerial, Optional<String> peerSerial) {
    }

    private static final Pattern ENABLED = Pattern.compile("(?is)<enabled>\\s*([^<]*?)\\s*</enabled>");
    private static final Pattern LOCAL_STATE = Pattern.compile("(?is)<local-info>.*?<state>\\s*([^<]*?)\\s*</state>");
    private static final Pattern MODE = Pattern.compile("(?is)<group>.*?<mode>\\s*([^<]*?)\\s*</mode>");
    private static final Pattern LOCAL_SERIAL = Pattern.compile("(?is)<local-info>.*?<serial-num>\\s*([^<]*?)\\s*</serial-num>");
    private static final Pattern PEER_SERIAL = Pattern.compile("(?is)<peer-info>.*?<serial-num>\\s*([^<]*?)\\s*</serial-num>");

    private PaloAltoHaStateParser() {
    }

    public static String normalizeSerial(String serial) {
        if (serial == null) {
            return null;
        }
        return serial.strip();
    }

    public static HaState parse(String output) {
        if (output == null || output.isBlank()) {
            return new HaState(UNKNOWN, Optional.empty(), Optional.empty(), Optional.empty());
        }
        Optional<String> enabled = firstMatch(ENABLED, output);
        if (enabled.isPresent() && "no".equalsIgnoreCase(enabled.get())) {
            return new HaState(STANDALONE, Optional.empty(), Optional.empty(), Optional.empty());
        }
        String role = firstMatch(LOCAL_STATE, output).map(s -> s.toUpperCase(Locale.ROOT)).orElse(UNKNOWN);
        Optional<String> localSerial = firstMatch(LOCAL_SERIAL, output).map(PaloAltoHaStateParser::normalizeSerial);
        Optional<String> peerSerial = firstMatch(PEER_SERIAL, output).map(PaloAltoHaStateParser::normalizeSerial);
        return new HaState(role, firstMatch(MODE, output), localSerial, peerSerial);
    }

    private static Optional<String> firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() && !matcher.group(1).isBlank() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
