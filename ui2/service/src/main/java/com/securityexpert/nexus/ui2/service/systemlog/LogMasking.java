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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.sanitizer.core.Counts;
import com.securityexpert.nexus.ui2.sanitizer.core.EstateDictionary;
import com.securityexpert.nexus.ui2.sanitizer.core.EstateIdentity;
import com.securityexpert.nexus.ui2.sanitizer.core.IdentityTokens;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.sanitizer.core.Mappers;
import com.securityexpert.nexus.ui2.sanitizer.core.SanitizerKey;
import com.securityexpert.nexus.ui2.sanitizer.core.TextSanitizer;
import com.securityexpert.nexus.ui2.sanitizer.core.TokenSink;
import com.securityexpert.nexus.ui2.sanitizer.format.TextEncoding;
import com.securityexpert.nexus.ui2.sanitizer.format.TextFormat;
import com.securityexpert.nexus.ui2.sanitizer.format.ValueRewriter;
import com.securityexpert.nexus.ui2.service.privacy.EstateDictionarySource;

/**
 * The aiview masking projection of stored system-log text (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 12:
 * "aiview always gets the masked projection ... applied at read time to every line and every file name").
 *
 * <p>It is the Data Sanitizer text path: the same {@link TextSanitizer} rules (prefix-preserving keyed IP permutation,
 * MAC, hostname and FQDN labels, e-mail, URL host, inventory device names with their {@code TopologyNamePseudonymizer}
 * pseudonyms, the serial rule) under the same HMAC key as the sanitizer and the UI masking, so a device reads the same
 * in an aiview answer, in a sanitized export and in the UI. Mapping tokens are discarded ({@link TokenSink#DISCARD}):
 * an aiview projection is one way and has no vault.</p>
 *
 * <p><b>Why this is in the service.</b> The sanitizer module may be used by the service only
 * ({@code SanitizerModuleFenceTest}), so the worker cannot call it and the read side lives here.</p>
 *
 * <p><b>Passes</b> ({@link #RULE_SET} names the set; the text page carries it so the UI can say which rules applied).
 * Each line goes through, in this order:</p>
 * <ol>
 *   <li>the <b>serial rule</b> ({@code SerialRule}, run by the text rules): a {@code serial} / {@code serial_number} /
 *       {@code serialno} / {@code sn} JSON value and a {@code serial=V} / {@code serial: V} pair become
 *       {@link IdentityTokens#serial}'s {@code SN-} token, the same token the inventory screens show. Fail closed: a JSON
 *       serial value is masked whatever its form;</li>
 *   <li>the <b>JSON identity field rule</b>: the string value of a host key ({@code device_name}, {@code host},
 *       {@code hostname}, any case and {@code -}/{@code _} spelling) becomes the inventory pseudonym when known,
 *       otherwise the text rules' host-context replacement (an address, host or FQDN label tokens), otherwise the
 *       generic {@code h-} host label token; the value of a principal key ({@code admin}, {@code admin_name},
 *       {@code user}, {@code username}) becomes a stable keyed user token {@code USR-} + 10 hex, one way. Each such
 *       value is masked once, by its key;</li>
 *   <li>the Data Sanitizer text rules on all other text;</li>
 *   <li>the <b>estate dictionary</b> ({@link EstateDictionary}, shared with the Data Sanitizer): any token (or
 *       {@code . _ : -}-delimited part of one) equal to an identity value the service knows is replaced by its masked
 *       form. It holds only keyed digests and the masked forms;</li>
 *   <li>the <b>heuristic free-text pass</b> ({@link FreeTextIdentityRules}, 2026-10-19): FQDN-shaped, hostname-shaped
 *       and serial-like tokens, principal mentions and (since {@code logmask-v4}) the word after a host keyword or
 *       {@code from}/{@code to} in free text, with a short documented allowlist.</li>
 * </ol>
 * <p>If the dictionary cannot be built and no earlier one is cached, {@link #session()} throws
 * {@link MaskingUnavailableException}: a masked page is refused rather than served with one pass missing.</p>
 *
 * <p>Use one {@link Session} per read request: it takes one snapshot of the inventory names, and the host names it
 * learns from host contexts in earlier lines carry to later lines of the same request. Sessions are not
 * thread-safe; this object is.</p>
 */
public final class LogMasking {

    /**
     * The masking rule set this class applies: {@code logmask-v1} the Data Sanitizer text rules (2026-10-17),
     * {@code logmask-v2} plus the serial and JSON identity-field rules and the estate dictionary (2026-10-18),
     * {@code logmask-v3} plus the heuristic free-text pass (2026-10-19), {@code logmask-v4} plus the host-context rules
     * of that pass (the word after a host keyword or {@code from}/{@code to}, the sar header host, the host of an HCP
     * report file name; 2026-10-21). Returned with every masked page ({@code X-Syslog-Mask-Rules}).
     */
    public static final String RULE_SET = "logmask-v4";

    /** {@code hcp_report_<host>_<dd>_<mm>_<yy>_<HH>_<MM>}: prefix, host, time parts. */
    private static final Pattern HCP_REPORT = Pattern.compile("(?i)(hcp_report_)(.+?)((?:_\\d{1,4}){5})");

    private static final java.util.Set<String> EXTENSIONS = java.util.Set.of("tar", "gz", "tgz", "zip", "txt", "log", "csv",
            "elg", "json", "pcap", "pcapng", "bz2", "xz");

    /** The estate dictionary could not be built and no earlier one is cached: no masked page may be served. */
    public static final class MaskingUnavailableException extends IllegalStateException {
        MaskingUnavailableException(Throwable cause) {
            super("the estate identity dictionary is unavailable (" + cause.getClass().getSimpleName() + ")", null);
        }
    }

    private final Mappers base;
    private final Supplier<KnownNames> knownNames;
    private final EstateDictionarySource estate;

    /**
     * Without an estate dictionary (tests, and wiring that has no device tables); the serial rule still applies.
     *
     * @param privacyKey the product's privacy HMAC key ({@code HmacKeyProvider#getSecretKey()})
     * @param knownNames the inventory's device names with their pseudonyms, read once per session; the service
     *        passes the same supplier the Data Sanitizer uses
     */
    public LogMasking(byte[] privacyKey, Supplier<KnownNames> knownNames) {
        this(privacyKey, knownNames, EstateDictionarySource.none(privacyKey));
    }

    /**
     * @param estate the identity values the service knows (serials, addresses, names), read at most once per
     *        {@code estateTtl}; only their keyed digests are kept
     */
    public LogMasking(byte[] privacyKey, Supplier<KnownNames> knownNames, Supplier<? extends List<EstateIdentity>> estate,
            Duration estateTtl) {
        this(privacyKey, knownNames, new EstateDictionarySource(privacyKey, estate, estateTtl));
    }

    /** @param estate the estate dictionary shared with the Data Sanitizer */
    public LogMasking(byte[] privacyKey, Supplier<KnownNames> knownNames, EstateDictionarySource estate) {
        Objects.requireNonNull(privacyKey, "privacyKey");
        this.base = new Mappers(SanitizerKey.of(privacyKey), KnownNames.NONE);
        this.knownNames = knownNames == null ? () -> KnownNames.NONE : knownNames;
        this.estate = Objects.requireNonNull(estate, "estate");
    }

    /**
     * A projection for one read request.
     *
     * @throws MaskingUnavailableException when the estate dictionary cannot be built and none is cached
     */
    public Session session() {
        EstateDictionary estateDictionary;
        try {
            estateDictionary = estate.get();
        } catch (EstateDictionarySource.UnavailableException e) {
            throw new MaskingUnavailableException(e.getCause() == null ? e : e.getCause());
        }
        KnownNames known = knownNames.get();
        return new Session(new Mappers(base.ip(), base.mac(), base.names(), known == null ? KnownNames.NONE : known,
                base.identity()), known, estateDictionary);
    }

    /** One request's projection. */
    public static final class Session {
        private final Mappers mappers;
        private final KnownNames known;
        private final Counts counts = new Counts();
        private final TextSanitizer text;
        private final IdentityTokens tokens;
        private final EstateDictionary.Replacer estate;
        private final FreeTextIdentityRules heuristics;

        private Session(Mappers mappers, KnownNames known, EstateDictionary estate) {
            this.mappers = mappers;
            this.known = known == null ? KnownNames.NONE : known;
            this.text = new TextSanitizer(mappers, TokenSink.DISCARD, counts);
            this.tokens = mappers.identity();
            this.estate = estate.replacer();
            this.heuristics = new FreeTextIdentityRules(mappers.names(), tokens);
        }

        /** One line (without its terminator). */
        public String maskLine(String line) {
            return line == null ? null : mask(line, false);
        }

        /** The rule set this session applies ({@link LogMasking#RULE_SET}). */
        public String ruleSet() {
            return RULE_SET;
        }

        /**
         * The passes over one value: JSON host and principal fields (each value masked once, by its key) with the text
         * rules (serial rule included) on everything between them; the estate dictionary; the heuristic pass.
         */
        private String mask(String value, boolean hostContext) {
            return heuristics.apply(estate.replace(identityFields(value, hostContext), counts), counts);
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
            return tokens.user(trimmed);
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
         * {@code hcp_report_<host>_<time>.tar.gz}; the estate dictionary and the heuristic pass follow on each token.
         * In that HCP report shape ({@code hcp_report_} + host + five numeric time parts, findings record 2026-10-17
         * section 8) the host part is masked whatever it looks like, a plain word included ({@code logmask-v4}).
         * Extensions are kept.
         */
        public String maskFileName(String name) {
            if (name == null || name.isEmpty()) {
                return name;
            }
            int stemEnd = stemEnd(name);
            String masked = name.substring(0, stemEnd);
            Matcher hcp = HCP_REPORT.matcher(masked);
            if (hcp.matches()) {
                String host = hcp.group(2);
                String generic = maskToken(host);
                if (!generic.equals(host)) return hcp.group(1) + generic + hcp.group(3) + name.substring(stemEnd);
                counts.add(TextSanitizer.HOSTNAME);
                return hcp.group(1) + mappers.names().label(host) + hcp.group(3) + name.substring(stemEnd);
            }
            StringBuilder out = new StringBuilder(masked.length() + 16);
            int start = 0;
            while (start <= masked.length()) {
                int end = masked.indexOf('_', start);
                if (end < 0) {
                    end = masked.length();
                }
                String token = masked.substring(start, end);
                out.append(maskToken(token));
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

        /** One file-name token through the text rules, the inventory names, the estate dictionary and the heuristics. */
        private String maskToken(String token) {
            return heuristics.apply(estate.replace(pseudonymOrSelf(text.sanitize(token)), counts), counts);
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
     * JSON keys whose string value is an identity, in the Palo Alto JSON Lines entries (findings record 2026-10-17:
     * {@code device_name} and {@code host} are host fields, {@code admin} the principal of a {@code pan_config}
     * entry). Keys are compared ignoring case, {@code -}, {@code _} and an {@code @} attribute prefix. Free text and
     * every other key keep the unchanged text rules.
     */
    static final class IdentityFields {
        enum Kind { HOST, PRINCIPAL }

        /** Count category of a principal replacement. */
        static final String PRINCIPAL = "principal";
        /** The {@link IdentityTokens#user} kind of a user token. */
        static final String USER_KIND = IdentityTokens.USER;

        private static final Set<String> HOST_KEYS = Set.of("devicename", "hostname", "host");
        private static final Set<String> PRINCIPAL_KEYS = Set.of("admin", "adminname", "user", "username");
        private static final Pattern FIELD = Pattern.compile("\"@?([A-Za-z0-9_-]{3,20})\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        private static final Pattern PREFILTER = Pattern.compile("(?i)\"@?(?:device[-_]?name|host(?:[-_]?name)?|admin(?:[-_]?name)?|user(?:[-_]?name)?)\"");
        static final Pattern ADDRESS_LITERAL = EstateDictionary.ADDRESS_LITERAL;
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
