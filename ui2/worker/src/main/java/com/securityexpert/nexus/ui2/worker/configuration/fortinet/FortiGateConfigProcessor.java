package com.securityexpert.nexus.ui2.worker.configuration.fortinet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;

/**
 * A FortiOS configuration ("show" at the top level, every VDOM) parsed to the product's configuration plane
 * (FORTINET_CONTRACT.md, PO 2026-09-26): each top-level "config ..." block is a section (per VDOM on a multi-VDOM box);
 * "set" lines are its settings; lines carrying a secret (a password, key, PSK, ENC value) are withheld and counted.
 * The canonical hash is over the withheld text without the per-run header, so an unchanged configuration hashes the same.
 */
public final class FortiGateConfigProcessor {

    public record Processed(String canonicalHash, int withheldLineCount, String sanitizedText, List<ConfigurationIndexEntry> index,
            int settingCount) {
    }

    static final List<String> SECRET_KEYWORDS = List.of("password", "passwd", "psksecret", "secret", "private-key", "privkey",
            "certificate", "auth-pwd", "priv-pwd", "key ", "passphrase", "token", " enc ");

    private FortiGateConfigProcessor() {
    }

    public static Processed process(String raw) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n").replace("\r", "\n");
        StringBuilder sanitized = new StringBuilder();
        Map<String, Integer> sectionCounts = new LinkedHashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        String vdom = "global";
        boolean inVdomList = false;
        int withheld = 0;
        int settings = 0;
        for (String rawLine : text.split("\n")) {
            String line = rawLine.stripTrailing();
            String t = line.strip();
            if (t.startsWith("#")) {
                continue; // the per-run header (config-version, conf_file_ver, buildno) never enters the hash or the text
            }
            if (t.startsWith("config ")) {
                String name = t.substring(7).strip();
                if (stack.isEmpty() && name.equals("vdom")) {
                    inVdomList = true;
                }
                if (stack.isEmpty() && name.equals("global")) {
                    vdom = "global";
                }
                stack.push(name);
            } else if (t.startsWith("edit ")) {
                if (inVdomList && stack.size() == 1) {
                    vdom = t.substring(5).strip().replace("\"", "");
                }
                stack.push("edit " + t.substring(5).strip());
            } else if (t.equals("next") || t.equals("end")) {
                if (!stack.isEmpty()) {
                    String popped = stack.pop();
                    if (t.equals("end") && popped.equals("vdom") && stack.isEmpty()) {
                        inVdomList = false;
                    }
                }
            } else if (t.startsWith("set ") || t.startsWith("unset ")) {
                settings++;
                if (containsSecret(t)) {
                    withheld++;
                    sanitized.append(indent(line)).append(t.split("\\s+")[0]).append(' ').append(t.split("\\s+")[1])
                            .append(" [withheld]\n");
                    countSection(sectionCounts, stack, vdom);
                    continue;
                }
                countSection(sectionCounts, stack, vdom);
            }
            sanitized.append(line).append('\n');
        }
        String out = sanitized.toString();
        List<ConfigurationIndexEntry> index = new ArrayList<>();
        sectionCounts.forEach((k, n) -> {
            int bar = k.indexOf('|');
            index.add(new ConfigurationIndexEntry(k.substring(0, bar), k.substring(bar + 1), Optional.empty(), n));
        });
        return new Processed(sha256(out), withheld, out, index, settings);
    }

    /** The section is the outermost config block, keyed by VDOM ("root|system interface"). */
    private static void countSection(Map<String, Integer> counts, Deque<String> stack, String vdom) {
        String outer = null;
        for (java.util.Iterator<String> it = stack.descendingIterator(); it.hasNext();) {
            String s = it.next();
            if (s.equals("vdom") || s.equals("global") || s.startsWith("edit ")) {
                continue;
            }
            outer = s;
            break;
        }
        if (outer == null) {
            return;
        }
        counts.merge(vdom + "|" + outer, 1, Integer::sum);
    }

    static boolean containsSecret(String setLine) {
        String lower = " " + setLine.toLowerCase(Locale.ROOT) + " ";
        return SECRET_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private static String indent(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return line.substring(0, i);
    }

    private static String sha256(String text) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : d.digest(text.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
