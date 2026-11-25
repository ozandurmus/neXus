package com.securityexpert.nexus.ui2.service.privacy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Deterministically pseudonymizes IPv4 addresses while preserving subnet membership,
 * prefix containment, and relative host offsets (PRIVATE_REPLAY_ARCHITECTURE §156).
 */
@Component
public class SubnetPreservingIpMasker {

    // No java.util.regex on caller-supplied text here (CodeQL java/polynomial-redos, alert 46): the full-string parse
    // and the free-text scan below are hand-written single-pass scanners. They reproduce exactly what the former
    // patterns matched -- ^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?:/(\d{1,2}))?$ with matches(), and
    // \b(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(?:/\d{1,2})?)\b with find() -- which
    // SubnetPreservingIpMaskerScannerTest proves differentially against those patterns.

    /** A parsed {@code a.b.c.d[/len]}: octets as written (not range-checked), {@code cidr} the length text or null. */
    record ParsedIpv4(int o1, int o2, int o3, int o4, String cidr) {}

    public record SubnetInfo(long netLong, long mask, int prefixLen, String netAddressStr) {}

    private final byte[] secretKey;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Map<String, SubnetInfo> registeredSubnets = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public SubnetPreservingIpMasker(HmacKeyProvider keyProvider) {
        this(keyProvider.getSecretKey());
    }

    public SubnetPreservingIpMasker(byte[] secretKey) {
        this.secretKey = Objects.requireNonNull(secretKey, "secretKey").clone();
    }

    /**
     * Registers a known subnet prefix (e.g. "192.0.2.0/23" or "192.0.2.12/23")
     * into the Longest Prefix Match (LPM) table so bare IPs (VIPs, gateways) inherit it.
     */
    public void registerSubnet(String cidrOrIp) {
        if (cidrOrIp == null || cidrOrIp.isBlank()) {
            return;
        }
        ParsedIpv4 parsed = parseIpv4(cidrOrIp.trim());
        if (parsed == null || parsed.cidr() == null) {
            return;
        }
        int o1 = parsed.o1();
        int o2 = parsed.o2();
        int o3 = parsed.o3();
        int o4 = parsed.o4();
        int prefixLen = Integer.parseInt(parsed.cidr());
        if (prefixLen <= 0 || prefixLen > 32 || o1 > 255 || o2 > 255 || o3 > 255 || o4 > 255) {
            return;
        }

        long ipLong = (((long) o1) << 24) | (((long) o2) << 16) | (((long) o3) << 8) | o4;
        long mask = (-1L << (32 - prefixLen)) & 0xFFFFFFFFL;
        long netLong = ipLong & mask;
        String netAddressStr = String.format("%d.%d.%d.%d",
                (netLong >> 24) & 0xFF, (netLong >> 16) & 0xFF, (netLong >> 8) & 0xFF, netLong & 0xFF);

        registeredSubnets.put(netAddressStr + "/" + prefixLen, new SubnetInfo(netLong, mask, prefixLen, netAddressStr));
    }

    /**
     * Masks an IPv4 address (e.g. "192.0.2.1" or "192.0.2.2/24").
     * Preserves loopback, default route, and subnet relationships.
     */
    public String mask(String ipOrCidr) {
        if (ipOrCidr == null || ipOrCidr.isBlank()) {
            return ipOrCidr;
        }
        String trimmed = ipOrCidr.trim();
        return cache.computeIfAbsent(trimmed, value -> computeMaskedIp(value, true));
    }

    /**
     * What {@link #mask} would answer for this value now, without any side effect: an already handed-out pseudonym is
     * returned as it is; otherwise the pseudonym is computed from the subnets registered so far and neither cached nor
     * registered. Path analysis (PO record 2026-11-03 D-3, condition 4) resolves aiview's masked query values with it,
     * so a resolution never warms a cache another viewer would see.
     */
    public String peek(String ipOrCidr) {
        if (ipOrCidr == null || ipOrCidr.isBlank()) {
            return ipOrCidr;
        }
        String trimmed = ipOrCidr.trim();
        String handedOut = cache.get(trimmed);
        return handedOut != null ? handedOut : computeMaskedIp(trimmed, false);
    }

    /**
     * The pseudonym of a value written {@code a.b.c.d/len} (a network, or a host with its prefix length as an interface
     * address is written), computed purely: nothing is cached and the network is not registered. Equals {@link #mask} of
     * the same text whatever was registered before or after: with an explicit length the subnet keyed hash depends only
     * on the network and its length, and the host offset is kept.
     */
    public String maskNetworkWithoutSideEffects(String cidr) {
        if (cidr == null || cidr.isBlank()) {
            return cidr;
        }
        return computeMaskedIp(cidr.trim(), false);
    }

    /**
     * The raw values already handed out as exactly this pseudonym, read back from the cache; reading it changes nothing.
     * Path analysis (security review 2026-11-05 M-4) uses it to find every raw host a masked response rendered as a value
     * aiview submits, so two of them are refused as ambiguous instead of one being picked. Never logged or returned to a
     * caller outside the service.
     */
    public java.util.List<String> handedOutAs(String pseudonym) {
        if (pseudonym == null || pseudonym.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<String> raw = new java.util.ArrayList<>();
        cache.forEach((value, masked) -> {
            if (pseudonym.equals(masked)) {
                raw.add(value);
            }
        });
        return raw;
    }

    /** The subnets registered so far ({@code a.b.c.d/len}), a copy; reading it changes nothing. */
    public java.util.List<String> registeredSubnets() {
        return java.util.List.copyOf(registeredSubnets.keySet());
    }

    /**
     * Finds any IPv4 addresses in free-form text (e.g. logs, terminal reasons)
     * and replaces them with their relationship-preserving masked counterpart. An IPv6 literal has no
     * relationship-preserving masker yet and fails closed to {@code [REDACTED_IP]} (the same answer {@link #mask} gives
     * an IPv6 value), so a Wireshark filter or a flow tuple that names one does not carry it past the projection.
     */
    public String maskText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        text = redactIpv6Literals(text);
        StringBuilder sb = new StringBuilder(text.length());
        int copied = 0;
        int from = 0;
        int[] span;
        while ((span = findEmbeddedIpv4(text, from)) != null) {
            sb.append(text, copied, span[0]).append(mask(text.substring(span[0], span[1])));
            copied = span[1];
            from = span[1];
        }
        sb.append(text, copied, text.length());
        return sb.toString();
    }

    private static final String IPV6_REDACTED = "[REDACTED_IP]";

    private static String redactIpv6Literals(String text) {
        if (text.indexOf(':') < 0) {
            return text;
        }
        StringBuilder sb = null;
        int copied = 0;
        int from = 0;
        int[] span;
        while ((span = findIpv6Leak(text, from)) != null) {
            from = span[1];
            if (isIpv6Literal(withoutIpv4Tail(text.substring(span[0], span[1])))) {
                if (sb == null) {
                    sb = new StringBuilder(text.length());
                }
                sb.append(text, copied, span[0]).append(IPV6_REDACTED);
                copied = span[1];
            }
        }
        if (sb == null) {
            return text;
        }
        return sb.append(text, copied, text.length()).toString();
    }

    /**
     * The next candidate IPv6 literal in {@code text} at or after {@code from}, as {@code {start, end}}, or null: a maximal
     * run of hex digits and colons that holds a hex digit directly followed by a colon, is not glued before to
     * {@code [0-9A-Za-z_:.]}, and is not followed by {@code [0-9A-Za-z_]} or by a dot and a digit. ({@link #isIpv6Literal}
     * then decides whether the candidate really is one: a MAC address, a time or a C++ scope is not.) One pass over the text,
     * no backtracking: every character is read a constant number of times. SubnetPreservingIpMaskerScannerTest proves it
     * differentially against the pattern this replaced,
     * {@code (?<![0-9A-Za-z_:.])[0-9A-Fa-f:]*[0-9A-Fa-f]:[0-9A-Fa-f:]*(?![0-9A-Za-z_:]|\.\d)}.
     */
    static int[] findIpv6Candidate(CharSequence text, int from) {
        int n = text.length();
        int i = from;
        while (i < n) {
            // A run can only start where the character before it is not a hex digit or colon (else it would be a longer
            // run) and not one of the lookbehind characters; skip anything that is not in the run alphabet.
            if (!isHexOrColon(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            boolean hexThenColon = false;
            while (i < n && isHexOrColon(text.charAt(i))) {
                if (text.charAt(i) == ':' && i > start && isHex(text.charAt(i - 1))) {
                    hexThenColon = true;
                }
                i++;
            }
            if (start > 0 && isIpv6LookbehindChar(text.charAt(start - 1))) {
                continue;
            }
            if (!hexThenColon) {
                continue;
            }
            if (i < n) {
                char next = text.charAt(i);
                if (isAsciiWordChar(next)) {
                    continue;
                }
                if (next == '.' && i + 1 < n && isAsciiDigit(text.charAt(i + 1))) {
                    continue;
                }
            }
            return new int[]{start, i};
        }
        return null;
    }

    /**
     * The next span in {@code text} at or after {@code from} that may be an IPv6 literal, as {@code {start, end}}, or
     * null. Unlike {@link #findIpv6Candidate} (the strict scanner proven against the former pattern) this one is built
     * to fail closed: a literal glued to a label ({@code peer:2001:db8::1}, {@code src:2001:db8::1}) or to a trailing
     * letter, and one that ends in a dotted IPv4 ({@code 2001:db8::192.0.2.33}), are found too. A run that follows a word
     * character gives up the glued label up to its first colon; a run that follows a dot is never a literal (it is the
     * tail of an IPv4 or a version). The caller still checks the shape with {@link #isIpv6Literal}. One pass, no
     * backtracking.
     */
    static int[] findIpv6Leak(CharSequence text, int from) {
        int n = text.length();
        int i = from;
        while (i < n) {
            if (!isHexOrColon(text.charAt(i))) {
                i++;
                continue;
            }
            int runStart = i;
            while (i < n && isHexOrColon(text.charAt(i))) {
                i++;
            }
            int start = runStart;
            if (start > 0) {
                char prev = text.charAt(start - 1);
                if (prev == '.') {
                    continue;
                }
                if (isAsciiWordChar(prev)) {
                    int c = start;
                    while (c < i && text.charAt(c) != ':') {
                        c++;
                    }
                    start = c + 1;
                    if (start >= i || text.charAt(start) == ':') {
                        continue; // no colon, or a "label::" scope operator
                    }
                }
            }
            boolean hexThenColon = false;
            for (int k = start + 1; k < i; k++) {
                if (text.charAt(k) == ':' && isHex(text.charAt(k - 1))) {
                    hexThenColon = true;
                    break;
                }
            }
            if (!hexThenColon) {
                continue;
            }
            int end = i;
            if (end < n && text.charAt(end) == '.' && end + 1 < n && isAsciiDigit(text.charAt(end + 1))) {
                int lastColon = end - 1;
                while (text.charAt(lastColon) != ':') {
                    lastColon--;
                }
                int j = end;
                while (j < n && (isAsciiDigit(text.charAt(j)) || text.charAt(j) == '.')) {
                    j++;
                }
                ParsedIpv4 tail = parseIpv4(text.subSequence(lastColon + 1, j).toString());
                if (tail != null && tail.cidr() == null && tail.o1() <= 255 && tail.o2() <= 255 && tail.o3() <= 255
                        && tail.o4() <= 255) {
                    end = j;
                }
            }
            return new int[]{start, end};
        }
        return null;
    }

    /** The candidate with a trailing dotted IPv4 replaced by the two groups it stands for, so the shape check can read it. */
    private static String withoutIpv4Tail(String candidate) {
        if (candidate.indexOf('.') < 0) {
            return candidate;
        }
        int lastColon = candidate.lastIndexOf(':');
        ParsedIpv4 tail = parseIpv4(candidate.substring(lastColon + 1));
        if (tail == null || tail.cidr() != null || tail.o1() > 255 || tail.o2() > 255 || tail.o3() > 255 || tail.o4() > 255) {
            return candidate;
        }
        return candidate.substring(0, lastColon + 1) + "0:0";
    }

    private static boolean isHex(int ch) {
        return isAsciiDigit(ch) || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
    }

    private static boolean isHexOrColon(int ch) {
        return ch == ':' || isHex(ch);
    }

    private static boolean isIpv6LookbehindChar(int ch) {
        return isAsciiWordChar(ch) || ch == ':' || ch == '.';
    }

    /**
     * Whether the text is an IPv6 literal by its shape alone: eight groups of one to four hex digits, or fewer around one
     * {@code ::}. The unspecified address and the loopback address describe protocol scope, not an estate identity, and
     * stay as they are (as 0.0.0.0 and 127.x do).
     */
    static boolean isIpv6Literal(String candidate) {
        if (candidate.equals("::") || candidate.equals("::1")) {
            return false;
        }
        int gap = candidate.indexOf("::");
        if (gap >= 0 && candidate.indexOf("::", gap + 1) >= 0) {
            return false;
        }
        String[] groups = candidate.split(":", -1);
        int present = 0;
        for (String group : groups) {
            if (group.isEmpty()) {
                continue; // the gap, or a leading/trailing colon of a gap
            }
            if (group.length() > 4) {
                return false;
            }
            present++;
        }
        if (gap >= 0) {
            // "::" stands for at least one all-zero group; a stray single leading or trailing colon is not a literal
            boolean edgeColonOk = (!candidate.startsWith(":") || candidate.startsWith("::"))
                    && (!candidate.endsWith(":") || candidate.endsWith("::"));
            return edgeColonOk && present <= 7;
        }
        return groups.length == 8 && present == 8;
    }

    /**
     * Parses the whole of {@code s} as exactly four dot-separated groups of one to three ASCII digits, optionally followed
     * by {@code /} and one or two ASCII digits, and nothing else. Null when {@code s} is not of that shape. Octets above
     * 255 and lengths above 32 are returned as written; callers range-check them.
     */
    static ParsedIpv4 parseIpv4(String s) {
        int n = s.length();
        int[] octets = new int[4];
        int i = 0;
        for (int k = 0; k < 4; k++) {
            if (k > 0) {
                if (i >= n || s.charAt(i) != '.') {
                    return null;
                }
                i++;
            }
            int start = i;
            int value = 0;
            while (i < n && i - start < 3 && isAsciiDigit(s.charAt(i))) {
                value = value * 10 + (s.charAt(i) - '0');
                i++;
            }
            if (i == start) {
                return null;
            }
            octets[k] = value;
        }
        String cidr = null;
        if (i < n) {
            if (s.charAt(i) != '/') {
                return null;
            }
            int start = ++i;
            while (i < n && i - start < 2 && isAsciiDigit(s.charAt(i))) {
                i++;
            }
            if (i == start) {
                return null;
            }
            cidr = s.substring(start, i);
        }
        return i == n ? new ParsedIpv4(octets[0], octets[1], octets[2], octets[3], cidr) : null;
    }

    /**
     * The next embedded {@code a.b.c.d[/len]} in {@code text} at or after {@code from}, as {@code {start, end}}, or null.
     * A token starts at an ASCII digit with a word boundary before it, has four groups of one to three ASCII digits, and
     * ends at a word boundary; the {@code /len} part (one or two digits) is kept only when a boundary follows it. Word
     * boundaries are those of {@link java.util.regex.Pattern}'s {@code \b} on this JDK (see {@link #isWordBoundary}).
     * Each candidate start reads a bounded number of characters ahead (four digits per group at most, three dots, '/' and
     * three digits), and the only backward walk crosses the run of non-spacing marks directly before that candidate, so
     * the scan is linear in the text length.
     */
    static int[] findEmbeddedIpv4(CharSequence text, int from) {
        int n = text.length();
        for (int start = from; start < n; start++) {
            if (!isAsciiDigit(text.charAt(start)) || !isWordBoundary(text, start)) {
                continue;
            }
            int end = matchEmbeddedAt(text, start);
            if (end >= 0) {
                return new int[]{start, end};
            }
        }
        return null;
    }

    /** End of the token starting at {@code start} (an ASCII digit with a boundary before it), or -1. */
    private static int matchEmbeddedAt(CharSequence text, int start) {
        int n = text.length();
        int i = start;
        for (int k = 0; k < 4; k++) {
            if (k > 0) {
                if (i >= n || text.charAt(i) != '.') {
                    return -1;
                }
                i++;
            }
            int groupStart = i;
            while (i < n && isAsciiDigit(text.charAt(i))) {
                i++;
                if (i - groupStart > 3) {
                    // A fourth digit: no shorter take of this group can be followed by '.' or by a boundary.
                    return -1;
                }
            }
            if (i == groupStart) {
                return -1;
            }
        }
        // i is just past the last octet's whole digit run, so text[i] (if any) is not an ASCII digit.
        if (i < n && text.charAt(i) == '/') {
            int cidrStart = i + 1;
            int j = cidrStart;
            while (j < n && isAsciiDigit(text.charAt(j)) && j - cidrStart < 3) {
                j++;
            }
            int len = j - cidrStart;
            if (len >= 1 && len <= 2 && isWordBoundary(text, j)) {
                return j;
            }
            // Without the length the token ends before '/', which is never a word character: always a boundary.
        }
        return isWordBoundary(text, i) ? i : -1;
    }

    /**
     * Whether {@code index} is a word boundary as {@code \b} decides it without UNICODE_CHARACTER_CLASS on Java 19+
     * (JDK-8264160): a word character is {@code [A-Za-z0-9_]}, or a non-spacing mark preceded, through further
     * non-spacing marks, by a letter or digit. The code point is read at the index and before it, as the JDK does.
     */
    private static boolean isWordBoundary(CharSequence text, int index) {
        int n = text.length();
        boolean left = false;
        if (index > 0) {
            int ch = Character.codePointBefore(text, index);
            left = isAsciiWordChar(ch)
                    || (Character.getType(ch) == Character.NON_SPACING_MARK && hasBaseCharacter(text, index - 1));
        }
        boolean right = false;
        if (index < n) {
            int ch = Character.codePointAt(text, index);
            right = isAsciiWordChar(ch)
                    || (Character.getType(ch) == Character.NON_SPACING_MARK && hasBaseCharacter(text, index));
        }
        return left != right;
    }

    /** The JDK's base-character walk for a non-spacing mark: back over marks to a letter or digit. */
    private static boolean hasBaseCharacter(CharSequence text, int index) {
        for (int x = index; x >= 0; x--) {
            int ch = Character.codePointAt(text, x);
            if (Character.isLetterOrDigit(ch)) {
                return true;
            }
            if (Character.getType(ch) != Character.NON_SPACING_MARK) {
                return false;
            }
        }
        return false;
    }

    private static boolean isAsciiDigit(int ch) {
        return ch >= '0' && ch <= '9';
    }

    private static boolean isAsciiWordChar(int ch) {
        return isAsciiDigit(ch) || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch == '_';
    }

    private String computeMaskedIp(String trimmed, boolean register) {
        ParsedIpv4 parsed = parseIpv4(trimmed);
        if (parsed == null) {
            return "[REDACTED_IP]";
        }

        int o1 = parsed.o1();
        int o2 = parsed.o2();
        int o3 = parsed.o3();
        int o4 = parsed.o4();

        if (o1 > 255 || o2 > 255 || o3 > 255 || o4 > 255) {
            return "[REDACTED_IP]";
        }

        String cidrGroup = parsed.cidr();
        int prefixLen;
        if (cidrGroup != null) {
            prefixLen = Integer.parseInt(cidrGroup);
            if (prefixLen < 0 || prefixLen > 32) {
                return "[REDACTED_IP]";
            }
            if (register) {
                registerSubnet(trimmed);
            }
        } else {
            prefixLen = 24; // default unless matched by LPM
        }

        // Unspecified/default, broadcast, loopback, link-local and multicast addresses
        // describe protocol scope rather than estate identities; preserve their routing meaning.
        if (o1 == 0 && o2 == 0 && o3 == 0 && o4 == 0) {
            return trimmed; // 0.0.0.0 or 0.0.0.0/0
        }
        if (o1 == 127) {
            return trimmed; // 127.x.x.x loopback
        }
        if (o1 == 255 && o2 == 255 && o3 == 255 && o4 == 255) {
            return trimmed; // 255.255.255.255 broadcast
        }

        if ((o1 == 169 && o2 == 254) || (o1 >= 224 && o1 <= 239)) {
            return trimmed; // link-local or multicast
        }

        long ipLong = (((long) o1) << 24) | (((long) o2) << 16) | (((long) o3) << 8) | o4;
        long mask = prefixLen == 0 ? 0 : (-1L << (32 - prefixLen)) & 0xFFFFFFFFL;
        long netLong = ipLong & mask;
        long hostOffset = ipLong & ~mask;
        String netAddressStr = String.format("%d.%d.%d.%d",
                (netLong >> 24) & 0xFF, (netLong >> 16) & 0xFF, (netLong >> 8) & 0xFF, netLong & 0xFF);

        // If no explicit CIDR was supplied, perform Longest Prefix Match (LPM)
        // against registered subnets to inherit containing network prefix
        if (cidrGroup == null) {
            SubnetInfo bestMatch = null;
            for (SubnetInfo info : registeredSubnets.values()) {
                if ((ipLong & info.mask()) == info.netLong()) {
                    if (bestMatch == null || info.prefixLen() > bestMatch.prefixLen()) {
                        bestMatch = info;
                    }
                }
            }
            if (bestMatch != null) {
                prefixLen = bestMatch.prefixLen();
                mask = bestMatch.mask();
                netLong = bestMatch.netLong();
                hostOffset = ipLong & ~mask;
                netAddressStr = bestMatch.netAddressStr();
            }
        }

        // Prefixes broader than /4 cannot preserve host offsets inside the reserved space.
        if (prefixLen < 4) {
            return "[REDACTED_IP]";
        }
        byte[] hmacBytes = hmacSha256("SUBNET:" + netAddressStr + "/" + prefixLen);
        int x = (hmacBytes[0] & 0xFF) % 240 + 10; // 10..249
        int y = (hmacBytes[1] & 0xFF) % 254 + 1;  // 1..254

        int firstOctet = 240 + (hmacBytes[2] & 0x0F); // reserved 240/4, 28 synthetic bits
        long synthNetBase = (((((long) firstOctet) << 24) | (((long) x) << 16) | (((long) y) << 8)) & mask);
        long synthIpLong = synthNetBase | hostOffset;
        // A broad prefix or HMAC collision can reproduce the source address. Never publish it as a pseudonym.
        if (synthIpLong == ipLong || synthIpLong == 0xFFFFFFFFL) {
            return "[REDACTED_IP]";
        }

        int so1 = (int) ((synthIpLong >> 24) & 0xFF);
        int so2 = (int) ((synthIpLong >> 16) & 0xFF);
        int so3 = (int) ((synthIpLong >> 8) & 0xFF);
        int so4 = (int) (synthIpLong & 0xFF);

        String syntheticIp = String.format("%d.%d.%d.%d", so1, so2, so3, so4);
        return cidrGroup != null ? syntheticIp + "/" + cidrGroup : syntheticIp;
    }

    private byte[] hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
