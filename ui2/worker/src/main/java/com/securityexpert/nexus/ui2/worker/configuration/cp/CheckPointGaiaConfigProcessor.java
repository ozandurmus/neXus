package com.securityexpert.nexus.ui2.worker.configuration.cp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;

/**
 * Turns one {@code show configuration} read into the two 13F CF-1/CF-2
 * outputs the sanitized view needs (the untouched bytes are stored
 * separately, unparsed, through the artefact store): the canonical hash
 * (14G CG-2 -- SHA-256 over {@code set} lines only, never the {@code #}
 * header, whose timestamp changes every read and would otherwise mark
 * every run "changed"), the sanitized text (14G CG-3: secret-bearing lines
 * withheld, counted, never included), and the section index (CG-3: first
 * one or two tokens after {@code set}).
 */
public final class CheckPointGaiaConfigProcessor {

    /** 14G CG-3's exact keyword list -- a line containing any of these (case-insensitive) is withheld. */
    public static final List<String> SECRET_KEYWORDS = List.of("password", "passwd", "secret", "community",
            "auth-key", "private-key", "pre-shared", "psk", "credential", "token");

    /** Measured two-token section names (CG-3); every other section is the line's first token after {@code set}. */
    private static final Set<String> TWO_TOKEN_SECTIONS = Set.of("ssh server", "snmp traps", "bonding group",
            "aaa radius-servers", "installer policy", "ssl tls", "arp table", "ntp server");

    public static final String CONTEXT_PHYSICAL = "physical";

    private CheckPointGaiaConfigProcessor() {
    }

    public record Processed(String canonicalHash, int withheldLineCount, String sanitizedText,
            List<ConfigurationIndexEntry> index) {
    }

    public static Processed process(String rawConfigurationText) {
        List<String> setLines = new ArrayList<>();
        for (String line : rawConfigurationText.split("\\R", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("set ")) {
                setLines.add(trimmed);
            }
        }

        String canonicalHash = sha256Hex(String.join("\n", setLines));

        Map<String, Integer> sectionCounts = new LinkedHashMap<>();
        StringBuilder sanitized = new StringBuilder();
        int withheld = 0;
        for (String line : setLines) {
            sectionCounts.merge(sectionOf(line), 1, Integer::sum);
            if (containsSecretKeyword(line)) {
                withheld++;
                continue;
            }
            sanitized.append(line).append('\n');
        }

        List<ConfigurationIndexEntry> index = sectionCounts.entrySet().stream()
                .map(e -> new ConfigurationIndexEntry(CONTEXT_PHYSICAL, e.getKey(), java.util.Optional.empty(),
                        e.getValue()))
                .toList();

        return new Processed(canonicalHash, withheld, sanitized.toString(), index);
    }

    private static boolean containsSecretKeyword(String setLine) {
        String lower = setLine.toLowerCase(Locale.ROOT);
        return SECRET_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private static String sectionOf(String setLine) {
        // setLine always starts with "set " (see process()); tokens[0] == "set".
        String[] tokens = setLine.split("\\s+");
        if (tokens.length < 2) {
            return "unknown";
        }
        if (tokens.length >= 3) {
            String twoToken = tokens[1] + " " + tokens[2];
            if (TWO_TOKEN_SECTIONS.contains(twoToken)) {
                return twoToken;
            }
        }
        return tokens[1];
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
