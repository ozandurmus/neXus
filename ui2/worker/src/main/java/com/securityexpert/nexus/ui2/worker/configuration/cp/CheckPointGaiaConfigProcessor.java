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
        CheckPointGaiaConfigParser parser = new CheckPointGaiaConfigParser();
        com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseContext context =
                new com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseContext(
                        "unknown",
                        com.securityexpert.nexus.ui2.worker.configuration.core.ConfigVendor.CHECK_POINT,
                        com.securityexpert.nexus.ui2.worker.configuration.core.ConfigFormat.GAIA_CLISH,
                        CONTEXT_PHYSICAL,
                        java.util.Optional.empty()
                );
        com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseResult res =
                parser.parseString(context, rawConfigurationText);
        return new Processed(
                res.canonicalHash().orElse(""),
                res.withheldLineCount(),
                res.sanitizedText(),
                res.index()
        );
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
