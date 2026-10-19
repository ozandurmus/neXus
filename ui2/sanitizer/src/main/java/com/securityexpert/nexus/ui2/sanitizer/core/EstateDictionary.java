package com.securityexpert.nexus.ui2.sanitizer.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.crypto.Mac;

/**
 * The known-identity pass (2026-10-18 privacy fix, moved here 2026-10-19 so the masked system-log view and the Data
 * Sanitizer share one implementation): any token, or {@code . _ : -}-delimited part of one, equal to an identity value
 * the service knows (device serials and recorded identities, management and interface addresses, host and cluster
 * names) is replaced by its masked form, so an unknown future format cannot leak a known identity.
 *
 * <p>The dictionary holds only keyed digests (HMAC-SHA256 under the privacy key) and the masked forms: the raw values
 * are read, digested and dropped by {@link #build}, and nothing about them is kept or printed. Matching is exact and
 * case-sensitive, like the UI's own masking (identifiers are opaque, AGENTS.md identity law), longest match first.
 * Immutable and thread-safe; each thread uses its own {@link Replacer}.</p>
 */
public final class EstateDictionary {

    /** Count category of a dictionary replacement. */
    public static final String ESTATE_IDENTITY = "estate_identity";
    public static final EstateDictionary EMPTY = new EstateDictionary(null, Map.of(), Set.of(), Integer.MAX_VALUE, 0);

    private static final int MIN_LENGTH = 4;
    /** At most this many delimited parts are joined into one candidate (an IPv6 address has eight). */
    private static final int MAX_PARTS = 16;
    private static final byte[] DOMAIN = "LOGMASK-ESTATE:".getBytes(StandardCharsets.UTF_8);
    private static final Set<String> STOPWORDS = Set.of("unknown", "none", "null", "localhost", "default", "n/a");
    private static final Pattern PREFIX_LENGTH = Pattern.compile("/[0-9]{1,3}$");
    /** A literal address (dotted quad or a run with a colon). */
    public static final Pattern ADDRESS_LITERAL = Pattern.compile("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}|[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");

    private final IdentityTokens tokens;
    private final Map<Long, String> masked;
    /** Length and first/last character of each value: a cheap filter before a token is digested. */
    private final Set<Integer> shapes;
    private final int minLength;
    private final int maxLength;

    private EstateDictionary(IdentityTokens tokens, Map<Long, String> masked, Set<Integer> shapes, int minLength, int maxLength) {
        this.tokens = tokens;
        this.masked = masked;
        this.shapes = shapes;
        this.minLength = minLength;
        this.maxLength = maxLength;
    }

    public int size() {
        return masked.size();
    }

    /**
     * @param base the installation's mappers (their identity tokens and the text rules give each masked form; their
     *        known names are not used)
     */
    public static EstateDictionary build(List<EstateIdentity> values, Mappers base) {
        if (values == null || values.isEmpty()) return EMPTY;
        IdentityTokens tokens = java.util.Objects.requireNonNull(base.identity(), "the mappers carry no identity tokens");
        // The text rules alone (no serial pre-pass, no known names): an address or a host name's own replacement.
        TextSanitizer rules = new TextSanitizer(new Mappers(base.ip(), base.mac(), base.names(), KnownNames.NONE),
                TokenSink.DISCARD, new Counts());
        Mac mac = tokens.newMac();
        Map<Long, String> masked = new HashMap<>();
        Set<Integer> shapes = new HashSet<>();
        int min = Integer.MAX_VALUE;
        int max = 0;
        // A value known as several kinds keeps the most specific masked form: a name, then an address, then a serial.
        for (EstateIdentity.Kind kind : List.of(EstateIdentity.Kind.NAME, EstateIdentity.Kind.ADDRESS, EstateIdentity.Kind.SERIAL)) {
            for (EstateIdentity identity : values) {
                if (identity == null || identity.kind() != kind || identity.value() == null) continue;
                String value = identity.value().strip();
                if (kind == EstateIdentity.Kind.ADDRESS) value = PREFIX_LENGTH.matcher(value).replaceFirst("");
                if (!eligible(value)) continue;
                String form = maskedForm(kind, value, identity.pseudonym(), rules, tokens);
                if (form == null || form.equals(value)) continue;
                if (masked.putIfAbsent(digest(mac, value), form) == null) {
                    shapes.add(shape(value, 0, value.length()));
                    min = Math.min(min, value.length());
                    max = Math.max(max, value.length());
                }
            }
        }
        return masked.isEmpty() ? EMPTY : new EstateDictionary(tokens, Map.copyOf(masked), Set.copyOf(shapes), min, max);
    }

    private static boolean eligible(String value) {
        if (value.length() < MIN_LENGTH || STOPWORDS.contains(value.toLowerCase(Locale.ROOT))) return false;
        if (value.chars().allMatch(Character::isDigit) && value.length() < 6) return false; // a port, a count
        if (!Character.isLetterOrDigit(value.charAt(0)) || !Character.isLetterOrDigit(value.charAt(value.length() - 1))) return false;
        return value.chars().allMatch(c -> isRunChar((char) c));
    }

    private static String maskedForm(EstateIdentity.Kind kind, String value, String pseudonym, TextSanitizer rules,
            IdentityTokens tokens) {
        switch (kind) {
            case SERIAL:
                return tokens.serial(value);
            case NAME: {
                if (pseudonym != null && !pseudonym.isBlank() && !"Unknown".equals(pseudonym)) return pseudonym;
                String host = rules.sanitize(value, true);
                return host.equals(value) ? tokens.payload(IdentityTokens.IDENTITY, value) : host;
            }
            default: {
                String address = rules.sanitize(value, false);
                if (!address.equals(value)) return address;
                // An address the text rules pass through (loopback, unspecified, multicast ...) is no identity.
                if (ADDRESS_LITERAL.matcher(value).matches()) return null;
                String host = rules.sanitize(value, true);
                return host.equals(value) ? tokens.payload(IdentityTokens.IDENTITY, value) : host;
            }
        }
    }

    /** A replacer for one thread (it owns a {@link Mac}). */
    public Replacer replacer() {
        return new Replacer(masked.isEmpty() ? null : tokens.newMac());
    }

    /** Applies the dictionary to text; not thread-safe. */
    public final class Replacer {
        private final Mac mac;

        private Replacer(Mac mac) {
            this.mac = mac;
        }

        /** Replaces every token or delimited part of a token that is a known identity value, longest match first. */
        public String replace(String line, Counts counts) {
            if (masked.isEmpty() || line == null || line.length() < minLength) return line;
            StringBuilder out = null;
            int copied = 0;
            int n = line.length();
            int i = 0;
            List<Integer> starts = new ArrayList<>();
            List<Integer> ends = new ArrayList<>();
            while (i < n) {
                if (!isRunChar(line.charAt(i))) {
                    i++;
                    continue;
                }
                int runStart = i;
                while (i < n && isRunChar(line.charAt(i))) i++;
                int runEnd = i;
                if (runEnd - runStart < minLength) continue;
                starts.clear();
                ends.clear();
                for (int p = runStart; p < runEnd; p++) {
                    if (Character.isLetterOrDigit(line.charAt(p)) && (p == runStart || !Character.isLetterOrDigit(line.charAt(p - 1))
                            && isSeparator(line.charAt(p - 1)))) starts.add(p);
                    int q = p + 1;
                    if (Character.isLetterOrDigit(line.charAt(p)) && (q == runEnd || isSeparator(line.charAt(q)))) ends.add(q);
                }
                int cursor = runStart;
                int firstEnd = 0;
                for (int s : starts) {
                    if (s < cursor) continue;
                    while (firstEnd < ends.size() && ends.get(firstEnd) <= s) firstEnd++;
                    int lastEnd = Math.min(ends.size(), firstEnd + MAX_PARTS) - 1;
                    for (int k = lastEnd; k >= firstEnd; k--) {
                        int e = ends.get(k);
                        int length = e - s;
                        if (length < minLength || length > maxLength || !shapes.contains(shape(line, s, e))) continue;
                        String form = masked.get(digest(mac, line.substring(s, e)));
                        if (form == null) continue;
                        if (out == null) out = new StringBuilder(n + 32);
                        out.append(line, copied, s).append(form);
                        copied = e;
                        cursor = e;
                        counts.add(ESTATE_IDENTITY);
                        break;
                    }
                }
            }
            return out == null ? line : out.append(line, copied, n).toString();
        }
    }

    private static boolean isRunChar(char c) {
        return Character.isLetterOrDigit(c) || isSeparator(c);
    }

    private static boolean isSeparator(char c) {
        return c == '.' || c == '_' || c == ':' || c == '-';
    }

    private static int shape(CharSequence s, int start, int end) {
        return ((end - start) << 16) | ((s.charAt(start) & 0xFF) << 8) | (s.charAt(end - 1) & 0xFF);
    }

    private static long digest(Mac mac, String value) {
        mac.update(DOMAIN);
        byte[] h = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        long v = 0;
        for (int b = 0; b < 8; b++) v = (v << 8) | (h[b] & 0xFF);
        return v;
    }

    @Override
    public String toString() {
        return "EstateDictionary[" + masked.size() + "]";
    }
}
