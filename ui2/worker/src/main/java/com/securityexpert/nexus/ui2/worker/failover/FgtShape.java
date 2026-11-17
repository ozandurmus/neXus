package com.securityexpert.nexus.ui2.worker.failover;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The masked shape of a FortiGate answer that did not parse: vendor field words and state words stay verbatim, every
 * other word becomes {@code a}, every digit {@code 9}, addresses, clocks and MACs become placeholders. Serials, host
 * names and interface names never survive (they are not in the vocabulary).
 */
final class FgtShape {
    private FgtShape() {}
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?:/\\d{1,2})?\\b");
    private static final String H16 = "[0-9a-f]{1,4}";
    private static final Pattern IPV6 = Pattern.compile("(?i)(?<![0-9a-f:.])(?:(?:" + H16 + ":){7}" + H16
        + "|(?:" + H16 + "(?::" + H16 + "){0,6})?::(?:" + H16 + "(?::" + H16 + "){0,6})?)(?:/\\d{1,3})?(?![0-9a-f:])");
    private static final Pattern MAC = Pattern.compile("(?i)\\b[0-9a-f]{2}(?::[0-9a-f]{2}){5}\\b");
    private static final Pattern CLOCK = Pattern.compile("\\b\\d{1,2}:\\d{2}:\\d{2}\\b");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z_-]*");
    static final Set<String> VOCABULARY = Set.of(
        "ha", "health", "status", "ok", "warning", "error", "model", "mode", "a-p", "a-a", "standalone", "group", "name", "id",
        "debug", "cluster", "uptime", "state", "change", "time", "primary", "secondary", "master", "slave", "selected", "using",
        "ses_pickup", "enable", "disable", "override", "configuration", "in-sync", "out-of-sync", "updated", "seconds", "ago",
        "system", "usage", "stats", "hbdev", "mondev", "up", "down", "physical", "index", "operating", "number", "of",
        "vcluster", "work", "checksum", "debugzone", "global", "root", "all", "failover", "set", "unset", "not", "is", "flag",
        "information", "group-id", "group-name", "uptime/reset_cnt", "ha_prio", "sessions", "memory", "average-cpu");

    static List<String> shape(String output, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (output == null) return lines;
        for (String line : output.split("\\R")) {
            if (line.isBlank()) continue;
            if (lines.size() >= maxLines) { lines.add("[more]"); break; }
            lines.add(shapeLine(line));
        }
        return lines;
    }

    static String shapeLine(String line) {
        String s = CLOCK.matcher(line).replaceAll("<time>");
        s = MAC.matcher(s).replaceAll("<mac>");
        s = IPV4.matcher(s).replaceAll("<ip>");
        s = IPV6.matcher(s).replaceAll("<ipsix>");
        StringBuilder out = new StringBuilder();
        Matcher m = WORD.matcher(s);
        int end = 0;
        while (m.find()) {
            out.append(digits(s.substring(end, m.start())));
            String w = m.group();
            out.append(VOCABULARY.contains(w.toLowerCase(java.util.Locale.ROOT)) ? w : "a");
            end = m.end();
        }
        out.append(digits(s.substring(end)));
        return out.toString();
    }
    private static String digits(String text) {
        return text.replaceAll("[0-9]", "9");
    }
}
