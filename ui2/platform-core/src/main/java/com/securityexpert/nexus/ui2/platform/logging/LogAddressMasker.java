package com.securityexpert.nexus.ui2.platform.logging;

import com.securityexpert.nexus.ui2.platform.OverwatchEnv;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The last-line masking of application log output (masking law): every IPv4 and IPv6 literal in a formatted log
 * message or stack trace is replaced by {@value #PLACEHOLDER} before it is written. This is a safety net behind the
 * call sites that already avoid logging addresses, not a replacement for them.
 *
 * <p>A fixed placeholder is used rather than the keyed pseudonyms of the read-time projection: a log line is written
 * once, in every process, with no privacy key or estate dictionary in reach.</p>
 *
 * <p>What is an address: a dotted quad whose four parts are decimal 0-255 without leading zeros and which is not part
 * of a longer dotted or digit run (so {@code 10.1.2}, {@code 1.2.3.4.5}, timestamps, durations and decimals survive;
 * a prefix length, a port or a trailing sentence period stay); an IPv6 literal that parses as one (eight groups, or
 * fewer with one {@code ::}, optionally ending in a dotted quad or carrying a {@code %zone}). MAC addresses and clock
 * times do not parse as IPv6 and survive. A bare four-part version such as {@code 10.1.2.3} is indistinguishable
 * from an address and is masked.</p>
 *
 * <p>Escape hatch: environment variable {@value #RAW_ENV} set to {@code 1} switches masking off. The code default is
 * masked; the live manifests in {@code deploy/ui2} set it so application logs on HOST-A show addresses (PO 2026-11-15).
 * Output that leaves HOST-A (aiview, diagnostics, exports, support bundles) does not depend on this layer.</p>
 */
public final class LogAddressMasker {

    public static final String PLACEHOLDER = "<ip>";
    /** Escape hatch; unset means masked. Set by the live manifests (PO 2026-11-15). */
    public static final String RAW_ENV = "NEXUS_LOG_RAW_ADDRESSES";

    private static final String OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![\\w.])(?<!\\d\\.)" + OCTET + "(?:\\." + OCTET + "){3}(?!\\d)(?!\\.\\d)");
    private static final Pattern IPV6_CANDIDATE = Pattern.compile(
            "(?<![\\w.])[0-9A-Fa-f:.]{2,}(?:%[A-Za-z0-9_.\\-]+)?(?![\\w:.])");
    private static final Pattern HEXTET = Pattern.compile("[0-9A-Fa-f]{1,4}");
    private static final Pattern STRICT_IPV4 = Pattern.compile(OCTET + "(?:\\." + OCTET + "){3}");

    private static final boolean RAW_FROM_ENV = rawAddressesEnabled(OverwatchEnv::get);

    private LogAddressMasker() {
    }

    /** @return true when the environment asks for raw addresses ({@value #RAW_ENV}{@code =1}). */
    public static boolean rawAddressesEnabled(Function<String, String> env) {
        return "1".equals(env.apply(RAW_ENV));
    }

    /** Masks according to the process environment. */
    public static String mask(String text) {
        return mask(text, RAW_FROM_ENV);
    }

    public static String mask(String text, boolean rawAddresses) {
        if (text == null || rawAddresses || text.length() < 7) {
            return text;
        }
        return maskIpv4(maskIpv6(text));
    }

    private static String maskIpv4(String text) {
        if (text.indexOf('.') < 0) {
            return text;
        }
        return IPV4.matcher(text).replaceAll(Matcher.quoteReplacement(PLACEHOLDER));
    }

    private static String maskIpv6(String text) {
        if (text.indexOf(':') < 0) {
            return text;
        }
        Matcher m = IPV6_CANDIDATE.matcher(text);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            String candidate = m.group();
            int keep = validLength(candidate);
            if (keep < 0) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length());
            }
            out.append(text, last, m.start()).append(PLACEHOLDER).append(candidate, keep, candidate.length());
            last = m.end();
        }
        if (out == null) {
            return text;
        }
        return out.append(text, last, text.length()).toString();
    }

    /**
     * @return the length of the leading part of {@code candidate} that is an IPv6 literal (zone included), so the
     *         rest (trailing punctuation) is kept; -1 when none is.
     */
    private static int validLength(String candidate) {
        String body = candidate;
        int zoneAt = candidate.indexOf('%');
        if (zoneAt >= 0) {
            body = candidate.substring(0, zoneAt);
        }
        if (isIpv6(body)) {
            return candidate.length();
        }
        if (zoneAt >= 0) {
            return -1;
        }
        // A sentence ends with punctuation: "... 2001:db8::1." or "... 2001:db8::1:".
        int end = body.length();
        while (end > 2 && (body.charAt(end - 1) == '.' || body.charAt(end - 1) == ':')) {
            end--;
            if (isIpv6(body.substring(0, end))) {
                return end;
            }
        }
        return -1;
    }

    static boolean isIpv6(String s) {
        if (s.indexOf(':') < 0 || s.contains(":::")) {
            return false;
        }
        int dbl = s.indexOf("::");
        if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) {
            return false;
        }
        int groups;
        if (dbl >= 0) {
            int head = countGroups(s.substring(0, dbl), false);
            int tail = countGroups(s.substring(dbl + 2), true);
            groups = head < 0 || tail < 0 ? -1 : head + tail;
            return groups >= 1 && groups <= 7;
        }
        groups = countGroups(s, true);
        return groups == 8;
    }

    /** Number of 16-bit groups in a colon-separated run ({@code -1} when malformed); a dotted quad counts two. */
    private static int countGroups(String part, boolean mayEndInIpv4) {
        if (part.isEmpty()) {
            return 0;
        }
        String[] items = part.split(":", -1);
        int groups = 0;
        for (int i = 0; i < items.length; i++) {
            String item = items[i];
            if (mayEndInIpv4 && i == items.length - 1 && item.indexOf('.') >= 0) {
                if (!STRICT_IPV4.matcher(item).matches()) {
                    return -1;
                }
                groups += 2;
            } else if (HEXTET.matcher(item).matches()) {
                groups++;
            } else {
                return -1;
            }
        }
        return groups;
    }
}
