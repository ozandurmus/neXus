package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The privacy guard of the system-log template census. Two checks, neither of which ever returns or logs a matched
 * value (only a class label and a count):
 *
 * <ul>
 *   <li>{@link #leak(String, String)} -- the masking projection permutes addresses and replaces names, so a masked
 *       line legitimately still contains address-shaped text. What it must never contain is an identity literal the
 *       <em>raw</em> line carried, unchanged. The caller, which holds both forms of one line inside the method that
 *       decrypted it, asks whether such a literal survived; the line is then counted and dropped, never mined. The
 *       ranges the permutation keeps on purpose (loopback, "this network", multicast, reserved/netmask space, the
 *       unspecified and loopback IPv6 addresses, IPv6 multicast) are not leaks.</li>
 *   <li>{@link #assertClean(String)} -- the finished report text must carry no IPv4, IPv6, MAC, e-mail or host-name
 *       shaped text at all (the census collapses all of them in templates); a hit fails the run.</li>
 * </ul>
 */
public final class CensusMaskGuard {

    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,24}");
    /** Group 1 is the dotted quad; an optional :port or /prefix is consumed with it. */
    static final Pattern IPV4_CANDIDATE = Pattern.compile(
            "(?<![0-9A-Za-z.])(\\d{1,3}(?:\\.\\d{1,3}){3})(?::\\d{1,5}|/\\d{1,2})?(?![0-9A-Za-z]|\\.\\d)");
    static final Pattern MAC = Pattern.compile(
            "(?<![0-9A-Fa-f:-])(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:-])"
                    + "|(?<![0-9A-Fa-f.])[0-9A-Fa-f]{4}\\.[0-9A-Fa-f]{4}\\.[0-9A-Fa-f]{4}(?![0-9A-Fa-f.])");
    /** Full, compressed and leading/trailing "::" forms; deliberately not "a:b:c" (times and ratios). */
    static final Pattern IPV6_TOKEN = Pattern.compile(
            "(?<![0-9A-Za-z:.])(?:(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}"
                    + "|(?:[0-9A-Fa-f]{1,4}:){1,6}(?::[0-9A-Fa-f]{1,4}){1,6}"
                    + "|(?:[0-9A-Fa-f]{1,4}:){1,7}:"
                    + "|:(?::[0-9A-Fa-f]{1,4}){1,7})(?:/\\d{1,3})?(?![0-9A-Za-z:])");
    /** Host names under a generic or local-network suffix; two-letter country suffixes are left out (file names such as x.so, x.sh). */
    static final Pattern FQDN = Pattern.compile(
            "(?i)(?<![A-Za-z0-9-])(?:[a-z0-9-]+\\.)+"
                    + "(?:com|net|org|edu|gov|mil|int|info|biz|io|local|lan|corp|internal|intranet|home|localdomain|arpa)(?![A-Za-z0-9-])");

    private CensusMaskGuard() {
    }

    /** A strictly valid dotted quad: four decimal octets, each 0..255, no leading zeros beyond a single digit. */
    static boolean isIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3 || (p.length() > 1 && p.charAt(0) == '0')) return false;
            int v = Integer.parseInt(p);
            if (v > 255) return false;
        }
        return true;
    }

    private static boolean keptIpv4(String quad) {
        int first = Integer.parseInt(quad.substring(0, quad.indexOf('.')));
        return first == 0 || first == 127 || first >= 224;
    }

    private static boolean keptIpv6(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        int slash = t.indexOf('/');
        if (slash >= 0) t = t.substring(0, slash);
        return t.equals("::") || t.equals("::1") || t.matches("ff[0-9a-f]{2}:.*");
    }

    /**
     * The class label of the first identity literal of {@code raw} that is still present, unchanged, in {@code masked}
     * ({@code ipv4}, {@code ipv6}, {@code mac}, {@code email}, {@code fqdn}); {@code null} when none survived.
     */
    public static String leak(String raw, String masked) {
        if (raw == null || masked == null) return null;
        boolean dot = raw.indexOf('.') >= 0;
        boolean colon = raw.indexOf(':') >= 0 || raw.indexOf('-') >= 0;
        if (raw.indexOf('@') >= 0 && survives(EMAIL, raw, masked, null)) return "email";
        if (dot && survivesIpv4(raw, masked)) return "ipv4";
        if (colon && survives(MAC, raw, masked, null)) return "mac";
        if (raw.indexOf(':') >= 0 && survives(IPV6_TOKEN, raw, masked, CensusMaskGuard::keptIpv6)) return "ipv6";
        if (dot && survives(FQDN, raw, masked, null)) return "fqdn";
        return null;
    }

    private static boolean survivesIpv4(String raw, String masked) {
        Matcher m = IPV4_CANDIDATE.matcher(raw);
        while (m.find()) {
            String quad = m.group(1);
            if (!isIpv4(quad) || keptIpv4(quad)) continue;
            if (containsBounded(masked, quad)) return true;
        }
        return false;
    }

    private static boolean survives(Pattern pattern, String raw, String masked, java.util.function.Predicate<String> kept) {
        Matcher m = pattern.matcher(raw);
        while (m.find()) {
            String literal = m.group();
            if (kept != null && kept.test(literal)) continue;
            if (containsBounded(masked, literal)) return true;
        }
        return false;
    }

    /** {@code needle} inside {@code hay} with no letter or digit directly before or after it. */
    static boolean containsBounded(String hay, String needle) {
        int from = 0;
        while (true) {
            int at = hay.indexOf(needle, from);
            if (at < 0) return false;
            int end = at + needle.length();
            boolean before = at == 0 || !Character.isLetterOrDigit(hay.charAt(at - 1));
            boolean after = end >= hay.length() || !Character.isLetterOrDigit(hay.charAt(end));
            if (before && after) return true;
            from = at + 1;
        }
    }

    /** The class labels of identity-shaped text in {@code text} (any IPv4, IPv6, MAC, e-mail, host name), without the values. */
    public static List<String> findings(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = IPV4_CANDIDATE.matcher(text);
        while (m.find()) {
            if (isIpv4(m.group(1))) {
                out.add("ipv4");
                break;
            }
        }
        if (MAC.matcher(text).find()) out.add("mac");
        if (IPV6_TOKEN.matcher(text).find()) out.add("ipv6");
        if (EMAIL.matcher(text).find()) out.add("email");
        if (FQDN.matcher(text).find()) out.add("fqdn");
        return out;
    }

    /** Fails with the class labels only (never a value) when {@code reportText} carries identity-shaped text. */
    public static void assertClean(String reportText) {
        List<String> findings = findings(reportText);
        if (!findings.isEmpty()) {
            throw new IllegalStateException("census output guard failed: identity-shaped text of class " + findings);
        }
    }
}
