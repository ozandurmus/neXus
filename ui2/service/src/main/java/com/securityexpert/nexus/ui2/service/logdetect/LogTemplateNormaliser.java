package com.securityexpert.nexus.ui2.service.logdetect;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The normaliser of layer B (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md section 4.1 step 2 and step 7). It works on a
 * line that {@code LogMasking} has <b>already masked</b> (the only text it is ever given) and turns every variable part
 * into a typed placeholder, so templates neither split per device on pseudonyms nor carry a value:
 *
 * <ul>
 *   <li>the phase B {@code <redacted>} marker as {@code <REDACTED>};</li>
 *   <li>every masking pseudonym class: device and cluster pseudonyms {@code <DEV>}, {@code h-} host labels
 *       {@code <HOST>}, {@code USR-} {@code <USR>}, {@code SN-} {@code <SN>}, estate names ({@code POL- RUL- OBJ- ZON- VSN-
 *       DMN- ID-}) {@code <EST>};</li>
 *   <li>quoted strings {@code <STR>}, URLs {@code <URL>}, e-mail forms {@code <EMAIL>}, addresses (masked IPv4/IPv6,
 *       with prefix or port) {@code <IP>}, MAC forms {@code <MAC>}, paths {@code <PATH>}, interface names {@code <IF>},
 *       the memory slot label {@code <DIMM_LABEL>}, dates {@code <DATE>}, times {@code <TIME>}, process ids
 *       {@code <PID>}, hex values {@code <HEX>} and numbers {@code <NUM>}.</li>
 * </ul>
 *
 * <p>Message shapes: {@code cp_messages} drops the syslog header (time and host) and keeps the process name as the
 * first key; {@code pan_system} keeps the vendor enumeration ({@code subtype/eventid:}) as the first key and normalises
 * the {@code opaque} text inside it. A line without a readable message gives no tokens (counted unparsed).</p>
 *
 * <p>Stored forms (section 4.1 step 7, PO question 6): {@link #displayForm} keeps a token only when every alphabetic run
 * in it (placeholders removed) is a word of the closed vocabulary ({@code template-vocabulary-v1.txt}) and every
 * character is in {@link #SAFE_CHARS}; any other token is {@code <W>}. {@link #matchForm} keeps the same tokens and
 * replaces each other token by a keyed one-way token ({@code k-} + 12 hex), so online matching is exact without ever
 * storing the word. Changing the placeholders or the vocabulary is a new {@link #VERSION}.</p>
 */
public final class LogTemplateNormaliser {
    /** Normaliser and vocabulary version; part of a finding's and a catalog's version set (design section 6.3). */
    public static final String VERSION = "lognorm-v1";
    public static final String VOCABULARY_VERSION = "vocab-v1";
    public static final String WORD = "<W>";
    /** At most this many tokens per message (the miner's own bound). */
    public static final int MAX_TOKENS = 64;
    /** A token longer than this is never kept as text. */
    static final int MAX_TOKEN_CHARS = 40;
    /** A line is cut here before it is read (bounded work per line). */
    static final int MAX_LINE_CHARS = 4000;
    /** The characters a stored template form may hold (the V273 CHECK on {@code template_text} and {@code match_form}). */
    public static final Pattern SAFE_CHARS = Pattern.compile("[\\[\\]A-Za-z0-9 <>_.:/#()=,*+%&|!?@~$^{}-]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("<(?:[A-Z][A-Z_]{0,15}|\\*)>");
    private static final Pattern ALPHA_RUN = Pattern.compile("[A-Za-z]+");

    private static final Pattern GAIA_HEADER = Pattern.compile(
            "^(?:<\\d{1,3}>)?[A-Za-z]{3}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2}(?:\\s+\\d{4})?\\s+\\S+\\s+(\\S.*)$");

    /** Ordered (pattern, placeholder) pairs; earlier rules win because their output no longer matches later ones. */
    private static final List<Object[]> RULES = List.of(
            rule("<redacted>", "<REDACTED>"),
            rule("\"[^\"]{0,400}\"|'[^']{0,400}'", "<STR>"),
            rule("(?i)\\b[a-z][a-z0-9+.-]{1,9}://[^\\s\"'<>]+", "<URL>"),
            rule("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,24}", "<EMAIL>"),
            rule("(?<![A-Za-z0-9])h-[0-9a-f]{10}(?:[@.](?:h-[0-9a-f]{10}|[A-Za-z]{2,24}))*(?![A-Za-z0-9])", "<HOST>"),
            rule("(?<![A-Za-z0-9])USR-[0-9A-F]{10}(?![A-Za-z0-9])", "<USR>"),
            rule("(?<![A-Za-z0-9])SN-[0-9A-F]{10}(?![A-Za-z0-9])", "<SN>"),
            rule("(?<![A-Za-z0-9])(?:POL|RUL|OBJ|ZON|VSN|DMN|ID)-[0-9A-F]{10}(?![A-Za-z0-9])", "<EST>"),
            rule("(?<![A-Za-z0-9])[A-Z]{2,4}(?:-[A-Z]+)+-M?\\d{1,3}(?:-[A-Z0-9]+)*(?![A-Za-z0-9])", "<DEV>"),
            rule("\\bCPU_SrcID#\\d{1,3}_(?:MC|Ha)#\\d{1,3}_Chan#\\d{1,3}_DIMM#\\d{1,3}\\b", "<DIMM_LABEL>"),
            rule("(?<![0-9A-Fa-f:-])[0-9A-Fa-f]{2}(?:[:-][0-9A-Fa-f]{2}){5}(?![0-9A-Fa-f:-])", "<MAC>"),
            rule("\\b\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)?\\b", "<DATE>"),
            rule("\\b\\d{1,2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\b", "<TIME>"),
            rule("(?<![0-9A-Za-z.])\\d{1,3}(?:\\.\\d{1,3}){3}(?:/\\d{1,2})?(?::\\d{1,5})?(?![0-9A-Za-z]|\\.\\d)", "<IP>"),
            rule("(?<![0-9A-Za-z:])(?=[0-9A-Fa-f:]*[0-9A-Fa-f])(?:[0-9A-Fa-f]{1,4}:){2,7}[0-9A-Fa-f]{1,4}(?:/\\d{1,3})?(?![0-9A-Za-z:])"
                    + "|(?<![0-9A-Za-z:])[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4})*::(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?(?:/\\d{1,3})?(?![0-9A-Za-z:])",
                    "<IP>"),
            rule("(?<![\\w<>/.-])/(?:[\\w.+@-]+/)*[\\w.+@-]+/?", "<PATH>"),
            rule("\\b(?:eth|bond|wrp|vpnt|Mgmt|mgmt|Sync|sync|ethernet|ae|tunnel|vlan|br|lo)\\d+(?:[./:]\\d+)*\\b", "<IF>"),
            rule("(?<=\\[)\\d{1,10}(?=\\])", "<PID>"),
            rule("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b", "<HEX>"),
            rule("\\b0[xX][0-9a-fA-F]+\\b", "<HEX>"),
            rule("\\b(?=[0-9a-fA-F]*\\d)(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{8,}\\b", "<HEX>"),
            rule("\\d+(?:\\.\\d+)*", "<NUM>"));

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Object[] rule(String regex, String placeholder) {
        return new Object[] {Pattern.compile(regex), Matcher.quoteReplacement(placeholder)};
    }

    private final Set<String> vocabulary;

    public LogTemplateNormaliser() {
        this(loadVocabulary());
    }

    LogTemplateNormaliser(Set<String> vocabulary) {
        this.vocabulary = Set.copyOf(vocabulary);
    }

    static Set<String> loadVocabulary() {
        Set<String> words = new TreeSet<>();
        try (InputStream in = LogTemplateNormaliser.class.getResourceAsStream("/logdetect/template-vocabulary-v1.txt")) {
            if (in == null) throw new IllegalStateException("template vocabulary resource missing");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null; ) {
                String w = line.strip();
                if (w.isEmpty() || w.startsWith("#")) continue;
                if (!w.matches("[a-z]{1,32}")) throw new IllegalStateException("vocabulary word outside [a-z]{1,32}");
                words.add(w);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return words;
    }

    /**
     * The normalised token sequence of one masked line of {@code sourceKey}, or an empty list when the line has no
     * readable message. The tokens may still hold words outside the vocabulary: they live in memory only and become
     * {@link #displayForm} or {@link #matchForm} before anything is stored.
     */
    public List<String> normalise(String sourceKey, String maskedLine) {
        if (maskedLine == null) return List.of();
        String line = maskedLine.length() > MAX_LINE_CHARS ? maskedLine.substring(0, MAX_LINE_CHARS) : maskedLine;
        String message = switch (sourceKey) {
            case LogEvidenceExtractor.SOURCE_MESSAGES -> gaiaMessage(line);
            case LogEvidenceExtractor.SOURCE_PAN_SYSTEM -> panMessage(line);
            default -> line.isBlank() ? null : line;
        };
        if (message == null || message.isBlank()) return List.of();
        String s = message;
        for (Object[] r : RULES) s = ((Pattern) r[0]).matcher(s).replaceAll((String) r[1]);
        String[] parts = s.strip().split("\\s+");
        List<String> tokens = new ArrayList<>(Math.min(parts.length, MAX_TOKENS));
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (tokens.size() == MAX_TOKENS) break;
            tokens.add(p);
        }
        return List.copyOf(tokens);
    }

    private static String gaiaMessage(String line) {
        Matcher m = GAIA_HEADER.matcher(line);
        return m.matches() ? m.group(1) : null;
    }

    /** {@code subtype/eventid: opaque} of the stored PAN entry (one JSON object); null when the line is not such an entry. */
    private static String panMessage(String line) {
        String t = line.strip();
        if (!t.startsWith("{")) return null;
        JsonNode n;
        try {
            n = JSON.readTree(t);
        } catch (IOException notJson) {
            return null;
        }
        if (n == null || !n.isObject()) return null;
        String subtype = text(n, "subtype");
        String eventId = text(n, "eventid");
        if (subtype == null || eventId == null) return null;
        String opaque = text(n, "opaque");
        String head = subtype + "/" + eventId + ":";
        return opaque == null || opaque.isBlank() ? head : head + " " + opaque;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isValueNode() && !v.isNull() ? v.asText().strip() : null;
    }

    /** Whether a token may be stored as it is: safe characters, bounded length, every alphabetic run a vocabulary word. */
    public boolean keeps(String token) {
        if (token.length() > MAX_TOKEN_CHARS || !SAFE_CHARS.matcher(token).matches()) return false;
        String rest = PLACEHOLDER.matcher(token).replaceAll(" ");
        Matcher run = ALPHA_RUN.matcher(rest);
        while (run.find()) {
            if (!vocabulary.contains(run.group().toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }

    /** The displayed and stored text: kept tokens as they are, every other token {@code <W>}. */
    public String displayForm(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(keeps(t) ? t : WORD);
        }
        return sb.toString();
    }

    /** The match form: kept tokens as they are, every other token a keyed one-way token (equality only, never the word). */
    public String matchForm(List<String> tokens, Function<String, String> keyedToken) {
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(keeps(t) || "<*>".equals(t) ? t : keyedToken.apply(t));
        }
        return sb.toString();
    }
}
