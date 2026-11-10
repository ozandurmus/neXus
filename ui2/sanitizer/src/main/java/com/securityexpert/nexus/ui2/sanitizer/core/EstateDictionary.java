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
 * <p><b>Estate names</b> ({@code logmask-v5}, 2026-11-10): administrator, policy, rule, object, zone, virtual-system and
 * MDS domain names ({@link EstateIdentity.Kind#isEstateName()}) are plain words with no shape, so they are matched
 * case-insensitively, and a multi-word name is matched as a phrase (its words joined by single spaces in the text).
 * Case folding here only widens what is masked; it never decides that two identifiers are equal. Device identities
 * stay exact and case-sensitive, like the UI's own masking (identifiers are opaque, AGENTS.md identity law). Longest match first; an exact device identity wins over an estate name of the same span. A token that
 * is already a masked form ({@code FW-TANGO-04}, {@code USR-...}, {@code h-...}) is never rewritten.</p>
 *
 * <p>The dictionary holds only keyed digests (HMAC-SHA256 under the privacy key) and the masked forms: the raw values
 * are read, digested and dropped by {@link #build}, and nothing about them is kept or printed. Immutable and
 * thread-safe; each thread uses its own {@link Replacer}.</p>
 */
public final class EstateDictionary {

    /** Count category of a dictionary replacement. */
    public static final String ESTATE_IDENTITY = "estate_identity";
    public static final EstateDictionary EMPTY = new EstateDictionary(null, Map.of(), Set.of(), Map.of(), Set.of(),
            Integer.MAX_VALUE, 0);

    private static final int MIN_LENGTH = 4;
    /** Estate names are words: three characters is a name ("Ops"); generic words are dropped before the build. */
    private static final int MIN_NAME_LENGTH = 3;
    /** At most this many delimited parts are joined into one candidate (an IPv6 address has eight; a phrase more). */
    private static final int MAX_PARTS = 24;
    private static final byte[] DOMAIN = "LOGMASK-ESTATE:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NAME_DOMAIN = "LOGMASK-ESTATE-NAME:".getBytes(StandardCharsets.UTF_8);
    private static final Set<String> STOPWORDS = Set.of("unknown", "none", "null", "localhost", "default", "n/a");
    private static final Pattern PREFIX_LENGTH = Pattern.compile("/[0-9]{1,3}$");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /** A literal address (dotted quad or a run with a colon). */
    public static final Pattern ADDRESS_LITERAL = Pattern.compile("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}|[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");
    /** A UI device, cluster, virtual-system or domain pseudonym ({@code FW-TANGO-04}, {@code VS-KILO-02-M1}). */
    public static final Pattern PSEUDONYM = Pattern.compile("(?:FW|CLS|VS|DOM)-[A-Z]+-\\d{2}(?:-(?:M\\d{1,2}|\\d{2}))?");

    private final IdentityTokens tokens;
    /** Device identities: exact, case-sensitive. */
    private final Map<Long, String> masked;
    /** Length and first/last character of each value: a cheap filter before a token is digested. */
    private final Set<Integer> shapes;
    /** Estate names: digest of the lower-cased value (single spaces between words). */
    private final Map<Long, String> names;
    /** Length and lower-cased first/last character of each estate name. */
    private final Set<Integer> nameShapes;
    private final int minLength;
    private final int maxLength;

    private EstateDictionary(IdentityTokens tokens, Map<Long, String> masked, Set<Integer> shapes, Map<Long, String> names,
            Set<Integer> nameShapes, int minLength, int maxLength) {
        this.tokens = tokens;
        this.masked = masked;
        this.shapes = shapes;
        this.names = names;
        this.nameShapes = nameShapes;
        this.minLength = minLength;
        this.maxLength = maxLength;
    }

    public int size() {
        return masked.size() + names.size();
    }

    /** The number of estate names ({@link EstateIdentity.Kind#isEstateName()}) held. */
    public int nameCount() {
        return names.size();
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
        Map<Long, String> names = new HashMap<>();
        Set<Integer> nameShapes = new HashSet<>();
        int min = Integer.MAX_VALUE;
        int max = 0;
        // A value known as several kinds keeps the most specific masked form: a name, then an address, then a serial;
        // among estate names, the kind order of EstateIdentity.Kind (user, policy, rule, object, zone, vsys, domain).
        for (EstateIdentity.Kind kind : EstateIdentity.Kind.values()) {
            for (EstateIdentity identity : values) {
                if (identity == null || identity.kind() != kind || identity.value() == null) continue;
                if (kind.isEstateName()) {
                    String value = SPACES.matcher(identity.value().strip()).replaceAll(" ");
                    if (!nameEligible(value)) continue;
                    String form = kind == EstateIdentity.Kind.USER ? tokens.user(value) : tokens.payload(kind.tokenKind(), value);
                    if (names.putIfAbsent(nameDigest(mac, value), form) == null) {
                        nameShapes.add(foldedShape(value, 0, value.length()));
                        min = Math.min(min, value.length());
                        max = Math.max(max, value.length());
                    }
                    continue;
                }
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
        if (masked.isEmpty() && names.isEmpty()) return EMPTY;
        return new EstateDictionary(tokens, Map.copyOf(masked), Set.copyOf(shapes), Map.copyOf(names), Set.copyOf(nameShapes),
                min, max);
    }

    /**
     * This dictionary with {@code other}'s entries added; on a value both hold, this one's masked form wins. Both must
     * be built under the same privacy key (the digests are keyed).
     */
    public EstateDictionary with(EstateDictionary other) {
        if (other == null || other.masked.isEmpty() && other.names.isEmpty()) return this;
        if (masked.isEmpty() && names.isEmpty()) return other;
        Map<Long, String> m = new HashMap<>(other.masked);
        m.putAll(masked);
        Map<Long, String> n = new HashMap<>(other.names);
        n.putAll(names);
        Set<Integer> s = new HashSet<>(other.shapes);
        s.addAll(shapes);
        Set<Integer> ns = new HashSet<>(other.nameShapes);
        ns.addAll(nameShapes);
        return new EstateDictionary(tokens, Map.copyOf(m), Set.copyOf(s), Map.copyOf(n), Set.copyOf(ns),
                Math.min(minLength, other.minLength), Math.max(maxLength, other.maxLength));
    }

    private static boolean eligible(String value) {
        if (value.length() < MIN_LENGTH || STOPWORDS.contains(value.toLowerCase(Locale.ROOT))) return false;
        if (value.chars().allMatch(Character::isDigit) && value.length() < 6) return false; // a port, a count
        if (!Character.isLetterOrDigit(value.charAt(0)) || !Character.isLetterOrDigit(value.charAt(value.length() - 1))) return false;
        return value.chars().allMatch(c -> isRunChar((char) c));
    }

    /** An estate name: a word or a phrase of words with a letter, not an address; generic words are the caller's filter. */
    private static boolean nameEligible(String value) {
        if (value.length() < MIN_NAME_LENGTH || STOPWORDS.contains(value.toLowerCase(Locale.ROOT))) return false;
        if (!Character.isLetterOrDigit(value.charAt(0)) || !Character.isLetterOrDigit(value.charAt(value.length() - 1))) return false;
        if (value.chars().noneMatch(Character::isLetter) || ADDRESS_LITERAL.matcher(value).matches()) return false;
        int parts = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isRunChar(c) && c != ' ') return false;
            if (Character.isLetterOrDigit(c) && (i + 1 == value.length() || !Character.isLetterOrDigit(value.charAt(i + 1)))) parts++;
        }
        return parts <= MAX_PARTS;
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
        return new Replacer(masked.isEmpty() && names.isEmpty() ? null : tokens.newMac());
    }

    /** Applies the dictionary to text; not thread-safe. */
    public final class Replacer {
        private final Mac mac;
        /** With estate names a space joins words into a phrase candidate; without, the scan is the device-only one. */
        private final boolean phrases = !names.isEmpty();
        private final List<Integer> starts = new ArrayList<>();
        private final List<Integer> ends = new ArrayList<>();

        private Replacer(Mac mac) {
            this.mac = mac;
        }

        /** Replaces every token or delimited part of a token that is a known identity value, longest match first. */
        public String replace(String line, Counts counts) {
            if (mac == null || line == null || line.length() < minLength) return line;
            StringBuilder out = null;
            int copied = 0;
            int n = line.length();
            int i = 0;
            while (i < n) {
                if (!isScanChar(line.charAt(i))) {
                    i++;
                    continue;
                }
                int runStart = i;
                while (i < n && isScanChar(line.charAt(i))) i++;
                int runEnd = i;
                if (runEnd - runStart < minLength) continue;
                starts.clear();
                ends.clear();
                for (int p = runStart; p < runEnd; p++) {
                    if (!Character.isLetterOrDigit(line.charAt(p))) continue;
                    if (p == runStart || !Character.isLetterOrDigit(line.charAt(p - 1)) && isJoiner(line.charAt(p - 1))) starts.add(p);
                    int q = p + 1;
                    if (q == runEnd || isJoiner(line.charAt(q))) ends.add(q);
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
                        if (length < minLength || length > maxLength) continue;
                        String form = lookup(line, s, e);
                        if (form == null) continue;
                        if (insideMaskedForm(line, s, e, runStart, runEnd)) break;
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

        /** The cheap shape filters first; a digest only for a candidate whose length and end characters fit. */
        private String lookup(String line, int s, int e) {
            boolean exact = shapes.contains(shape(line, s, e));
            boolean folded = phrases && nameShapes.contains(foldedShape(line, s, e));
            if (!exact && !folded) return null;
            if (exact && (!phrases || line.indexOf(' ', s) < 0 || line.indexOf(' ', s) >= e)) {
                String form = masked.get(digest(mac, line.substring(s, e)));
                if (form != null) return form;
            }
            return folded ? names.get(nameDigest(mac, line.substring(s, e))) : null;
        }

        private boolean isScanChar(char c) {
            return isRunChar(c) || c == ' ' && phrases;
        }

        private boolean isJoiner(char c) {
            return isSeparator(c) || c == ' ' && phrases;
        }
    }

    /**
     * True when the match sits inside a token that is already a masked form (a pseudonym, an identity token, a host
     * label token): such a token is never rewritten, so {@code FW-TANGO-04} stays itself even when an object is named
     * "tango".
     */
    private static boolean insideMaskedForm(String line, int s, int e, int runStart, int runEnd) {
        int a = s;
        while (a > runStart && line.charAt(a - 1) != ' ') a--;
        int b = s;
        while (b < runEnd && line.charAt(b) != ' ') b++;
        String token = line.substring(a, b);
        return PSEUDONYM.matcher(token).matches() || IdentityTokens.isToken(token) || NameMapper.isLabelToken(token);
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

    private static int foldedShape(CharSequence s, int start, int end) {
        return ((end - start) << 16) | ((Character.toLowerCase(s.charAt(start)) & 0xFF) << 8)
                | (Character.toLowerCase(s.charAt(end - 1)) & 0xFF);
    }

    private static long digest(Mac mac, String value) {
        mac.update(DOMAIN);
        return first8(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static long nameDigest(Mac mac, String value) {
        mac.update(NAME_DOMAIN);
        return first8(mac.doFinal(value.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
    }

    private static long first8(byte[] h) {
        long v = 0;
        for (int b = 0; b < 8; b++) v = (v << 8) | (h[b] & 0xFF);
        return v;
    }

    @Override
    public String toString() {
        return "EstateDictionary[" + size() + "]";
    }
}
