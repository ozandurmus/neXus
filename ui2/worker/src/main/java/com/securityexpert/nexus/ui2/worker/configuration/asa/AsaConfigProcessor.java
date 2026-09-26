package com.securityexpert.nexus.ui2.worker.configuration.asa;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;

/** Sanitizes a single-context ASA running configuration before indexing or presentation. */
public final class AsaConfigProcessor {

    public record Processed(String canonicalHash, int withheldLineCount, String sanitizedText,
            List<ConfigurationIndexEntry> index, int settingCount) {
    }

    private AsaConfigProcessor() {
    }

    public static Processed process(String raw) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sanitized = new StringBuilder();
        Map<String, Integer> counts = new LinkedHashMap<>();
        String section = null;
        boolean hasChildren = false;
        boolean certificateChain = false;
        int withheld = 0;
        int settings = 0;
        for (String rawLine : text.split("\n")) {
            String line = rawLine.stripTrailing();
            String trimmed = line.strip();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty() || trimmed.equals("!") || volatileHeader(lower)) {
                continue;
            }
            boolean child = !line.isEmpty() && Character.isWhitespace(line.charAt(0));
            if (!child) {
                if (section != null && !hasChildren) {
                    counts.merge(section, 1, Integer::sum);
                    settings++;
                }
                section = sectionOf(trimmed);
                hasChildren = false;
                certificateChain = lower.startsWith("crypto ca certificate chain ");
            } else if (section != null) {
                hasChildren = true;
                counts.merge(section, 1, Integer::sum);
                settings++;
            }
            if (certificateChain || secret(lower)) {
                withheld++;
                String keyword = certificateChain ? (child ? "certificate" : "crypto ca certificate chain")
                        : trimmed.split("\\s+", 2)[0];
                sanitized.append(child ? " " : "").append(keyword).append(" [withheld]\n");
            } else {
                sanitized.append(line).append('\n');
            }
        }
        if (section != null && !hasChildren) {
            counts.merge(section, 1, Integer::sum);
            settings++;
        }
        List<ConfigurationIndexEntry> index = new ArrayList<>();
        counts.forEach((name, count) -> index.add(new ConfigurationIndexEntry("single", name, Optional.empty(), count)));
        String out = sanitized.toString();
        return new Processed(sha256(out), withheld, out, index, settings);
    }

    private static boolean volatileHeader(String lower) {
        return lower.equals(":") || lower.startsWith(": saved") || lower.startsWith(": written by")
                || lower.startsWith(": serial number") || lower.startsWith(": hardware")
                || lower.startsWith("cryptochecksum:");
    }

    private static boolean secret(String lower) {
        return lower.contains("password") || lower.contains("passwd") || lower.contains("secret")
                || lower.contains("key") || lower.contains("snmp-server community") || lower.contains("passphrase");
    }

    private static String sectionOf(String line) {
        String[] parts = line.split("\\s+");
        int words = (line.startsWith("object network ") || line.startsWith("crypto map ")) ? 2 : 1;
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < Math.min(words, parts.length); i++) {
            if (i > 0) name.append(' ');
            String[] hyphenated = parts[i].split("-", -1);
            for (int j = 0; j < hyphenated.length; j++) {
                if (j > 0) name.append(' ');
                String word = hyphenated[j];
                if (!word.isEmpty()) name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return name.toString();
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
