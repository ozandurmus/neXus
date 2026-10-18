package com.securityexpert.nexus.ui2.service.systemlog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.securityexpert.nexus.ui2.sanitizer.core.Counts;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.sanitizer.core.Mappers;
import com.securityexpert.nexus.ui2.sanitizer.core.SanitizerKey;
import com.securityexpert.nexus.ui2.sanitizer.core.TextSanitizer;
import com.securityexpert.nexus.ui2.sanitizer.core.TokenSink;
import com.securityexpert.nexus.ui2.sanitizer.format.TextEncoding;
import com.securityexpert.nexus.ui2.sanitizer.format.TextFormat;
import com.securityexpert.nexus.ui2.sanitizer.format.ValueRewriter;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * The aiview masking projection of stored system-log text (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 12:
 * "aiview always gets the masked projection ... applied at read time to every line and every file name").
 *
 * <p>It is the Data Sanitizer text path and nothing else: the same {@link TextSanitizer} rules (prefix-preserving
 * keyed IP permutation, MAC, hostname and FQDN labels, e-mail, URL host, inventory device names with their
 * {@code TopologyNamePseudonymizer} pseudonyms) under the same HMAC key as the sanitizer and the UI masking, so a
 * device reads the same in an aiview answer, in a sanitized export and in the UI. No rule is reimplemented here.
 * Mapping tokens are discarded ({@link TokenSink#DISCARD}): an aiview projection is one way and has no vault.</p>
 *
 * <p><b>Why this is in the service.</b> The sanitizer module may be used by the service only
 * ({@code SanitizerModuleFenceTest}), so the worker cannot call it and the read side lives here.</p>
 *
 * <p><b>Two passes beyond the text rules (2026-10-18 privacy fix).</b> The Data Sanitizer text rules have no serial
 * rule, so the {@code serial} field that every Palo Alto {@code pan_system} / {@code pan_config} JSON Lines entry
 * carries reached aiview unmasked (the in-cluster e2e canary found a 12-digit serial on every line of the "config"
 * source). Each line therefore goes through, in this order:</p>
 * <ol>
 *   <li>the <b>serial field rule</b>: the value of a JSON key {@code serial}, {@code serial_number}, {@code serialno},
 *       {@code sn} (any case, {@code -}/{@code _} spelling, quoted or a bare number) and of a {@code serial=V} /
 *       {@code serial: V} pair becomes {@link TopologyNamePseudonymizer#maskSerial}'s {@code SN-} token, the same token
 *       the inventory screens show for that serial. Fail closed: a JSON serial value is masked whatever its form;</li>
 *   <li>the <b>JSON identity field rule</b>: the string value of a host key ({@code device_name}, {@code host},
 *       {@code hostname}, any case and {@code -}/{@code _} spelling) becomes the inventory pseudonym when known,
 *       otherwise the text rules' host-context replacement (an address, host or FQDN label tokens), otherwise the
 *       generic {@code h-} host label token; the value of a principal key ({@code admin}, {@code admin_name},
 *       {@code user}, {@code username}) becomes a stable keyed user token {@code USR-} + 10 hex
 *       ({@link TopologyNamePseudonymizer#maskPayloadToken}), one way. Each such value is masked once, by its key;</li>
 *   <li>the Data Sanitizer text rules (unchanged) on all other text;</li>
 *   <li>the <b>estate dictionary</b>: any token (or {@code . _ : -}-delimited part of one) equal to an identity value
 *       the service knows (device serials and recorded identities, management and interface addresses, host and
 *       cluster names) is replaced by its masked form, so an unknown future format cannot leak a known identity. The
 *       dictionary holds only keyed digests (HMAC under the privacy key) and the masked forms; the raw values are
 *       read, digested and dropped when it is built, and nothing about them is logged.</li>
 * </ol>
 * <p>If the dictionary cannot be built and no earlier one is cached, {@link #session()} throws
 * {@link MaskingUnavailableException}: a masked page is refused rather than served with one pass missing.</p>
 *
 * <p>Use one {@link Session} per read request: it takes one snapshot of the inventory names, and the host names it
 * learns from host contexts in earlier lines carry to later lines of the same request. Sessions are not
 * thread-safe; this object is.</p>
 */
public final class LogMasking {

    private static final java.util.Set<String> EXTENSIONS = java.util.Set.of("tar", "gz", "tgz", "zip", "txt", "log", "csv",
            "elg", "json", "pcap", "pcapng", "bz2", "xz");

    /** How an estate identity value's masked form is made. */
    public enum IdentityKind {
        /** A serial or a recorded device identity: {@code SN-} + 10 hex ({@link TopologyNamePseudonymizer#maskSerial}). */
        SERIAL,
        /** A management or interface address (or a host name used as one): the text rules' own replacement. */
        ADDRESS,
        /** A device or cluster name: its UI pseudonym ({@code FW-...}, {@code CLS-...}). */
        NAME
    }

    /**
     * One identity value the service knows, handed to the dictionary builder and dropped after it. {@code pseudonym}
     * is the UI pseudonym of a {@link IdentityKind#NAME} (null for the other kinds). {@link #toString()} never prints
     * the value.
     */
    public record EstateIdentity(IdentityKind kind, String value, String pseudonym) {
        public EstateIdentity {
            Objects.requireNonNull(kind, "kind");
        }

        public static EstateIdentity serial(String value) {
            return new EstateIdentity(IdentityKind.SERIAL, value, null);
        }

        public static EstateIdentity address(String value) {
            return new EstateIdentity(IdentityKind.ADDRESS, value, null);
        }

        public static EstateIdentity name(String value, String pseudonym) {
            return new EstateIdentity(IdentityKind.NAME, value, pseudonym);
        }

        @Override
        public String toString() {
            return "EstateIdentity[" + kind + "]";
        }
    }

    /** The estate dictionary could not be built and no earlier one is cached: no masked page may be served. */
    public static final class MaskingUnavailableException extends IllegalStateException {
        MaskingUnavailableException(Throwable cause) {
            super("the estate identity dictionary is unavailable (" + cause.getClass().getSimpleName() + ")", null);
        }
    }

    private static final System.Logger LOG = System.getLogger(LogMasking.class.getName());

    private final Mappers base;
    private final Supplier<KnownNames> knownNames;
    private final Supplier<? extends List<EstateIdentity>> estate;
    private final long estateTtlNanos;
    private final byte[] digestKey;
    private final TopologyNamePseudonymizer pseudonyms;
    private volatile EstateDictionary dictionary;
    private volatile long dictionaryExpiresAtNanos;

    /**
     * Without an estate dictionary (tests, and wiring that has no device tables); the serial field rule still applies.
     *
     * @param privacyKey the product's privacy HMAC key ({@code HmacKeyProvider#getSecretKey()})
     * @param knownNames the inventory's device names with their pseudonyms, read once per session; the service
     *        passes the same supplier the Data Sanitizer uses
     */
    public LogMasking(byte[] privacyKey, Supplier<KnownNames> knownNames) {
        this(privacyKey, knownNames, List::of, Duration.ofDays(1));
    }

    /**
     * @param estate the identity values the service knows (serials, addresses, names), read at most once per
     *        {@code estateTtl}; only their keyed digests are kept
     */
    public LogMasking(byte[] privacyKey, Supplier<KnownNames> knownNames, Supplier<? extends List<EstateIdentity>> estate,
            Duration estateTtl) {
        Objects.requireNonNull(privacyKey, "privacyKey");
        this.base = new Mappers(SanitizerKey.of(privacyKey), KnownNames.NONE);
        this.knownNames = knownNames == null ? () -> KnownNames.NONE : knownNames;
        this.estate = estate == null ? List::of : estate;
        this.estateTtlNanos = Objects.requireNonNull(estateTtl, "estateTtl").toNanos();
        this.digestKey = privacyKey.clone();
        this.pseudonyms = new TopologyNamePseudonymizer(privacyKey);
    }

    /**
     * A projection for one read request.
     *
     * @throws MaskingUnavailableException when the estate dictionary cannot be built and none is cached
     */
    public Session session() {
        EstateDictionary estateDictionary = dictionary();
        KnownNames known = knownNames.get();
        return new Session(new Mappers(base.ip(), base.mac(), base.names(), known == null ? KnownNames.NONE : known), known,
                pseudonyms, estateDictionary, newMac(digestKey));
    }

    private EstateDictionary dictionary() {
        EstateDictionary current = dictionary;
        long now = System.nanoTime();
        if (current != null && now - dictionaryExpiresAtNanos < 0) return current;
        synchronized (this) {
            current = dictionary;
            if (current != null && now - dictionaryExpiresAtNanos < 0) return current;
            try {
                EstateDictionary fresh = EstateDictionary.build(estate.get(), base, pseudonyms, newMac(digestKey));
                dictionary = fresh;
                dictionaryExpiresAtNanos = System.nanoTime() + estateTtlNanos;
                return fresh;
            } catch (RuntimeException e) {
                if (current != null) {
                    // Keep serving with the previous dictionary; the next request tries again.
                    LOG.log(System.Logger.Level.WARNING, "system-log estate dictionary refresh failed, previous one kept cause={0}",
                            e.getClass().getSimpleName());
                    return current;
                }
                LOG.log(System.Logger.Level.ERROR, "system-log estate dictionary unavailable cause={0}", e.getClass().getSimpleName());
                throw new MaskingUnavailableException(e);
            }
        }
    }

    private static Mac newMac(byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** One request's projection. */
    public static final class Session {
        private final Mappers mappers;
        private final KnownNames known;
        private final Counts counts = new Counts();
        private final TextSanitizer text;
        private final TopologyNamePseudonymizer pseudonyms;
        private final EstateDictionary estate;
        private final Mac mac;

        private Session(Mappers mappers, KnownNames known, TopologyNamePseudonymizer pseudonyms, EstateDictionary estate,
                Mac mac) {
            this.mappers = mappers;
            this.known = known == null ? KnownNames.NONE : known;
            this.text = new TextSanitizer(mappers, TokenSink.DISCARD, counts);
            this.pseudonyms = pseudonyms;
            this.estate = estate;
            this.mac = mac;
        }

        /** One line (without its terminator). */
        public String maskLine(String line) {
            return line == null ? null : mask(line, false);
        }

        /**
         * The passes over one value: serial fields; JSON host and principal fields (each value masked once, by its
         * key) with the text rules on everything between them; the estate dictionary.
         */
        private String mask(String value, boolean hostContext) {
            String serialsMasked = SerialFields.mask(value, pseudonyms, counts);
            return estate.replace(identityFields(serialsMasked, hostContext), mac, counts);
        }

        private String identityFields(String line, boolean hostContext) {
            Matcher m = IdentityFields.matcher(line);
            if (m == null) return text.sanitize(line, hostContext);
            StringBuilder out = new StringBuilder(line.length() + 32);
            int copied = 0;
            while (m.find()) {
                IdentityFields.Kind kind = IdentityFields.kind(m.group(1));
                if (kind == null) continue;
                String value = m.group(2);
                String masked = kind == IdentityFields.Kind.HOST ? hostValue(value) : principalValue(value);
                if (masked.equals(value)) continue;
                // Text between identity values goes through the text rules; the value itself is masked once, by its key.
                out.append(text.sanitize(line.substring(copied, m.start(2)), false)).append(masked);
                copied = m.end(2);
            }
            if (copied == 0) return text.sanitize(line, hostContext);
            return out.append(text.sanitize(line.substring(copied), false)).toString();
        }

        /**
         * A value under a host key, masked whatever it is: the inventory pseudonym when known, else the text rules in
         * host context (an address, a host or FQDN label token), else the generic host label token. Only an address
         * the text rules pass through (loopback, unspecified ...) and an empty value stay.
         */
        private String hostValue(String value) {
            String trimmed = value.strip();
            if (trimmed.isEmpty() || trimmed.contains("<redacted>")) return value;
            Optional<String> pseudonym = known.pseudonymFor(trimmed);
            if (pseudonym.isPresent()) {
                counts.add(TextSanitizer.DEVICE_NAME);
                return pseudonym.get();
            }
            String masked = text.sanitize(trimmed, true);
            if (!masked.equals(trimmed)) return masked;
            if (IdentityFields.ADDRESS_LITERAL.matcher(trimmed).matches()) return value;
            counts.add(TextSanitizer.HOSTNAME);
            return mappers.names().label(trimmed);
        }

        /** A value under a principal key: a stable keyed user token ({@code USR-} + 10 hex), never reversible here. */
        private String principalValue(String value) {
            String trimmed = value.strip();
            if (trimmed.isEmpty() || trimmed.contains("<redacted>") || IdentityFields.USER_TOKEN.matcher(trimmed).matches()) {
                return value;
            }
            counts.add(IdentityFields.PRINCIPAL);
            return pseudonyms.maskPayloadToken(IdentityFields.USER_KIND, trimmed);
        }

        private ValueRewriter rewriter() {
            return values -> {
                List<String> out = new ArrayList<>(values.size());
                for (var v : values) out.add(mask(v.text(), v.hostContext()));
                return out;
            };
        }

        /**
         * A stored chunk: UTF-8 when the whole chunk is valid UTF-8, otherwise ISO-8859-1 (one byte, one character,
         * so a legacy code page survives byte for byte). Every line goes through the rules and its terminator
         * ({@code LF}, {@code CRLF}, {@code CR}, none) is written back exactly.
         */
        public byte[] maskChunk(byte[] chunk) {
            Objects.requireNonNull(chunk, "chunk");
            Charset charset = isValidUtf8(chunk) ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
            ByteArrayOutputStream out = new ByteArrayOutputStream(chunk.length + 64);
            try {
                TextFormat.process(new ByteArrayInputStream(chunk), out, new TextEncoding(charset, 0), rewriter());
            } catch (IOException e) {
                throw new UncheckedIOException(e); // in-memory streams: not reachable
            }
            return out.toByteArray();
        }

        /**
         * A file name. The text rules run on each {@code _}-separated token of the name (an underscore is a word
         * character for them, so an address or host name inside {@code capture_192.0.2.9.pcap} is not delimited
         * in the whole name), and a token that is an inventory device name is replaced by its pseudonym, as in
         * {@code hcp_report_<host>_<time>.tar.gz}. Extensions are kept.
         */
        public String maskFileName(String name) {
            if (name == null || name.isEmpty()) {
                return name;
            }
            int stemEnd = stemEnd(name);
            String masked = name.substring(0, stemEnd);
            StringBuilder out = new StringBuilder(masked.length() + 16);
            int start = 0;
            while (start <= masked.length()) {
                int end = masked.indexOf('_', start);
                if (end < 0) {
                    end = masked.length();
                }
                String token = masked.substring(start, end);
                out.append(estate.replace(pseudonymOrSelf(text.sanitize(SerialFields.mask(token, pseudonyms, counts))), mac, counts));
                if (end < masked.length()) {
                    out.append('_');
                }
                start = end + 1;
            }
            return out.append(name, stemEnd, name.length()).toString();
        }

        /**
         * Where the name's extensions begin: trailing {@code .tar .gz .tgz .zip .txt .log .csv .elg .json .pcap}
         * and rotation numbers ({@code .1}), which the text rules would otherwise read as a public suffix or a
         * label of a longer host name. They carry no identity and are appended back unchanged.
         */
        private static int stemEnd(String name) {
            int end = name.length();
            boolean first = true;
            while (true) {
                int dot = name.lastIndexOf('.', end - 1);
                if (dot <= 0) {
                    return end;
                }
                String ext = name.substring(dot + 1, end);
                boolean rotation = first && !ext.isEmpty() && ext.chars().allMatch(Character::isDigit)
                        && !Character.isDigit(name.charAt(dot - 1)); // "messages.1", never the tail of "192.0.2.9"
                if (!EXTENSIONS.contains(ext.toLowerCase(java.util.Locale.ROOT)) && !rotation) {
                    return end;
                }
                end = dot;
                first = false;
            }
        }

        /** Replacement counts per category since this session began (counts only, never content). */
        public Map<String, Long> counts() {
            return counts.asMap();
        }

        private String pseudonymOrSelf(String token) {
            if (token.isEmpty()) {
                return token;
            }
            Optional<String> whole = known.pseudonymFor(token);
            if (whole.isPresent()) {
                counts.add(TextSanitizer.DEVICE_NAME);
                return whole.get();
            }
            return token;
        }

        @Override
        public String toString() {
            return "LogMasking.Session[" + mappers.getClass().getSimpleName() + "]";
        }
    }

    /**
     * The serial field rule. Palo Alto log entries are stored as JSON Lines with a {@code "serial"} key on every entry
     * (findings record 2026-10-17: always present in {@code pan_system} and {@code pan_config}); other formats write
     * {@code serial=V} or {@code Serial Number: V}. The value becomes the inventory's {@code SN-} token.
     */
    static final class SerialFields {
        /** Count category of a serial replacement. */
        static final String SERIAL = "serial";

        private static final String KEY = "(?:serial(?:[-_ ]?(?:number|num|no))?|sn)";
        /** {@code "serial":"V"} or {@code "serial": 1234} (any case, an {@code @} attribute prefix allowed). */
        private static final Pattern JSON = Pattern.compile(
                "(?i)\"@?" + KEY + "\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|(-?[0-9][0-9.eE+-]*))");
        /** {@code serial=V}, {@code serial: V}, {@code Serial Number = "V"}: the value must carry a digit. */
        private static final Pattern TEXT = Pattern.compile(
                "(?i)(?<![A-Za-z0-9_])" + KEY + "\\s*[=:]\\s*([\"']?)([A-Za-z0-9][A-Za-z0-9._/:-]*)");
        private static final Pattern TOKEN = Pattern.compile("SN-[0-9A-F]{10}");
        private static final Pattern HAS_DIGIT = Pattern.compile(".*[0-9].*");

        private SerialFields() {
        }

        static String mask(String text, TopologyNamePseudonymizer pseudonyms, Counts counts) {
            if (text == null || text.length() < 4) return text;
            String lower = text.toLowerCase(Locale.ROOT);
            if (!lower.contains("serial") && !lower.contains("sn")) return text;
            return text(json(text, pseudonyms, counts), pseudonyms, counts);
        }

        private static String json(String text, TopologyNamePseudonymizer pseudonyms, Counts counts) {
            Matcher m = JSON.matcher(text);
            StringBuilder out = null;
            int copied = 0;
            while (m.find()) {
                boolean quoted = m.start(1) >= 0;
                int start = quoted ? m.start(1) : m.start(2);
                int end = quoted ? m.end(1) : m.end(2);
                String value = text.substring(start, end).strip();
                // Fail closed: a JSON serial is masked whatever its form; only an empty or already masked value stays.
                if (value.isEmpty() || isMasked(value)) continue;
                if (out == null) out = new StringBuilder(text.length() + 16);
                String token = pseudonyms.maskSerial(value);
                out.append(text, copied, start).append(quoted ? token : "\"" + token + "\"");
                copied = end;
                counts.add(SERIAL);
            }
            return out == null ? text : out.append(text, copied, text.length()).toString();
        }

        private static String text(String text, TopologyNamePseudonymizer pseudonyms, Counts counts) {
            Matcher m = TEXT.matcher(text);
            StringBuilder out = null;
            int copied = 0;
            while (m.find()) {
                int start = m.start(2);
                int end = m.end(2);
                while (end > start && ".:/-".indexOf(text.charAt(end - 1)) >= 0) end--;
                String value = text.substring(start, end);
                if (start < copied || value.isEmpty() || isMasked(value) || !HAS_DIGIT.matcher(value).matches()) continue;
                if (out == null) out = new StringBuilder(text.length() + 16);
                out.append(text, copied, start).append(pseudonyms.maskSerial(value));
                copied = end;
                counts.add(SERIAL);
            }
            return out == null ? text : out.append(text, copied, text.length()).toString();
        }

        private static boolean isMasked(String value) {
            return TOKEN.matcher(value).matches() || value.contains("<redacted>");
        }
    }

    /**
     * JSON keys whose string value is an identity, in the Palo Alto JSON Lines entries (findings record 2026-10-17:
     * {@code device_name} and {@code host} are host fields, {@code admin} the principal of a {@code pan_config}
     * entry). Keys are compared ignoring case, {@code -}, {@code _} and an {@code @} attribute prefix. Free text and
     * every other key keep the unchanged text rules.
     */
    static final class IdentityFields {
        enum Kind { HOST, PRINCIPAL }

        /** Count category of a principal replacement. */
        static final String PRINCIPAL = "principal";
        /** The {@link TopologyNamePseudonymizer#maskPayloadToken} kind of a user token. */
        static final String USER_KIND = "USR";

        private static final Set<String> HOST_KEYS = Set.of("devicename", "hostname", "host");
        private static final Set<String> PRINCIPAL_KEYS = Set.of("admin", "adminname", "user", "username");
        private static final Pattern FIELD = Pattern.compile("\"@?([A-Za-z0-9_-]{3,20})\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        private static final Pattern PREFILTER = Pattern.compile("(?i)\"@?(?:device[-_]?name|host(?:[-_]?name)?|admin(?:[-_]?name)?|user(?:[-_]?name)?)\"");
        static final Pattern ADDRESS_LITERAL = Pattern.compile("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}|[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");
        static final Pattern USER_TOKEN = Pattern.compile(USER_KIND + "-[0-9A-F]{10}");

        private IdentityFields() {
        }

        /** A matcher over the line's JSON string fields, or null when the line has no identity key at all. */
        static Matcher matcher(String line) {
            if (line == null || line.indexOf('"') < 0 || !PREFILTER.matcher(line).find()) return null;
            return FIELD.matcher(line);
        }

        static Kind kind(String key) {
            String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
            if (HOST_KEYS.contains(normalized)) return Kind.HOST;
            if (PRINCIPAL_KEYS.contains(normalized)) return Kind.PRINCIPAL;
            return null;
        }
    }

    /**
     * The estate dictionary: keyed digests of the identity values the service knows, each with its masked form. Raw
     * values are never kept: a token is digested and looked up. Immutable; the {@link Mac} is the caller's.
     */
    static final class EstateDictionary {
        /** Count category of a dictionary replacement. */
        static final String ESTATE_IDENTITY = "estate_identity";
        static final EstateDictionary EMPTY = new EstateDictionary(Map.of(), Set.of(), Integer.MAX_VALUE, 0);

        private static final int MIN_LENGTH = 4;
        /** At most this many delimited parts are joined into one candidate (an IPv6 address has eight). */
        private static final int MAX_PARTS = 16;
        private static final byte[] DOMAIN = "LOGMASK-ESTATE:".getBytes(StandardCharsets.UTF_8);
        private static final Set<String> STOPWORDS = Set.of("unknown", "none", "null", "localhost", "default", "n/a");
        private static final Pattern PREFIX_LENGTH = Pattern.compile("/[0-9]{1,3}$");
        private static final Pattern ADDRESS_LITERAL = IdentityFields.ADDRESS_LITERAL;

        private final Map<Long, String> masked;
        /** Length and first/last character of each value: a cheap filter before a token is digested. */
        private final Set<Integer> shapes;
        private final int minLength;
        private final int maxLength;

        private EstateDictionary(Map<Long, String> masked, Set<Integer> shapes, int minLength, int maxLength) {
            this.masked = masked;
            this.shapes = shapes;
            this.minLength = minLength;
            this.maxLength = maxLength;
        }

        int size() {
            return masked.size();
        }

        static EstateDictionary build(List<EstateIdentity> values, Mappers base, TopologyNamePseudonymizer pseudonyms, Mac mac) {
            if (values == null || values.isEmpty()) return EMPTY;
            TextSanitizer rules = new TextSanitizer(base, TokenSink.DISCARD, new Counts());
            Map<Long, String> masked = new HashMap<>();
            Set<Integer> shapes = new HashSet<>();
            int min = Integer.MAX_VALUE;
            int max = 0;
            // A value known as several kinds keeps the most specific masked form: a name, then an address, then a serial.
            for (IdentityKind kind : List.of(IdentityKind.NAME, IdentityKind.ADDRESS, IdentityKind.SERIAL)) {
                for (EstateIdentity identity : values) {
                    if (identity == null || identity.kind() != kind || identity.value() == null) continue;
                    String value = identity.value().strip();
                    if (kind == IdentityKind.ADDRESS) value = PREFIX_LENGTH.matcher(value).replaceFirst("");
                    if (!eligible(value)) continue;
                    String form = maskedForm(kind, value, identity.pseudonym(), rules, pseudonyms);
                    if (form == null || form.equals(value)) continue;
                    // Exact and case-sensitive, like the UI's own masking: identifiers are opaque (AGENTS.md identity law).
                    if (masked.putIfAbsent(digest(mac, value), form) == null) {
                        shapes.add(shape(value, 0, value.length()));
                        min = Math.min(min, value.length());
                        max = Math.max(max, value.length());
                    }
                }
            }
            return masked.isEmpty() ? EMPTY : new EstateDictionary(Map.copyOf(masked), Set.copyOf(shapes), min, max);
        }

        private static boolean eligible(String value) {
            if (value.length() < MIN_LENGTH || STOPWORDS.contains(value.toLowerCase(Locale.ROOT))) return false;
            if (value.chars().allMatch(Character::isDigit) && value.length() < 6) return false; // a port, a count
            if (!Character.isLetterOrDigit(value.charAt(0)) || !Character.isLetterOrDigit(value.charAt(value.length() - 1))) return false;
            return value.chars().allMatch(c -> isRunChar((char) c));
        }

        private static String maskedForm(IdentityKind kind, String value, String pseudonym, TextSanitizer rules,
                TopologyNamePseudonymizer pseudonyms) {
            switch (kind) {
                case SERIAL:
                    return pseudonyms.maskSerial(value);
                case NAME: {
                    if (pseudonym != null && !pseudonym.isBlank() && !"Unknown".equals(pseudonym)) return pseudonym;
                    String host = rules.sanitize(value, true);
                    return host.equals(value) ? pseudonyms.maskPayloadToken("ID", value) : host;
                }
                default: {
                    String address = rules.sanitize(value, false);
                    if (!address.equals(value)) return address;
                    // An address the text rules pass through (loopback, unspecified, multicast ...) is no identity.
                    if (ADDRESS_LITERAL.matcher(value).matches()) return null;
                    String host = rules.sanitize(value, true);
                    return host.equals(value) ? pseudonyms.maskPayloadToken("ID", value) : host;
                }
            }
        }

        /** Replaces every token or delimited part of a token that is a known identity value, longest match first. */
        String replace(String line, Mac mac, Counts counts) {
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
    }

    private static boolean isValidUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
