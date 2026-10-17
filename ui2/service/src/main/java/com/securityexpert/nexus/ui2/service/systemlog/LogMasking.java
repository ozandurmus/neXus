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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.securityexpert.nexus.ui2.sanitizer.core.Counts;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.sanitizer.core.Mappers;
import com.securityexpert.nexus.ui2.sanitizer.core.SanitizerKey;
import com.securityexpert.nexus.ui2.sanitizer.core.TextSanitizer;
import com.securityexpert.nexus.ui2.sanitizer.core.TokenSink;
import com.securityexpert.nexus.ui2.sanitizer.format.TextEncoding;
import com.securityexpert.nexus.ui2.sanitizer.format.TextFormat;
import com.securityexpert.nexus.ui2.sanitizer.format.ValueRewriter;

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
 * <p>Use one {@link Session} per read request: it takes one snapshot of the inventory names, and the host names it
 * learns from host contexts in earlier lines carry to later lines of the same request. Sessions are not
 * thread-safe; this object is.</p>
 */
public final class LogMasking {

    private static final java.util.Set<String> EXTENSIONS = java.util.Set.of("tar", "gz", "tgz", "zip", "txt", "log", "csv",
            "elg", "json", "pcap", "pcapng", "bz2", "xz");

    private final Mappers base;
    private final Supplier<KnownNames> knownNames;

    /**
     * @param privacyKey the product's privacy HMAC key ({@code HmacKeyProvider#getSecretKey()})
     * @param knownNames the inventory's device names with their pseudonyms, read once per session; the service
     *        passes the same supplier the Data Sanitizer uses
     */
    public LogMasking(byte[] privacyKey, Supplier<KnownNames> knownNames) {
        this.base = new Mappers(SanitizerKey.of(Objects.requireNonNull(privacyKey, "privacyKey")), KnownNames.NONE);
        this.knownNames = knownNames == null ? () -> KnownNames.NONE : knownNames;
    }

    /** A projection for one read request. */
    public Session session() {
        KnownNames known = knownNames.get();
        return new Session(new Mappers(base.ip(), base.mac(), base.names(), known == null ? KnownNames.NONE : known), known);
    }

    /** One request's projection. */
    public static final class Session {
        private final Mappers mappers;
        private final KnownNames known;
        private final Counts counts = new Counts();
        private final TextSanitizer text;

        private Session(Mappers mappers, KnownNames known) {
            this.mappers = mappers;
            this.known = known == null ? KnownNames.NONE : known;
            this.text = new TextSanitizer(mappers, TokenSink.DISCARD, counts);
        }

        /** One line (without its terminator). */
        public String maskLine(String line) {
            return line == null ? null : text.sanitize(line);
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
                TextFormat.process(new ByteArrayInputStream(chunk), out, new TextEncoding(charset, 0),
                        ValueRewriter.sanitizing(text));
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
                out.append(pseudonymOrSelf(text.sanitize(token)));
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
