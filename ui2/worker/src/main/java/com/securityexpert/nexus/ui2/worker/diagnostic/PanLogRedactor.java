package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secret redaction for Palo Alto log entries, applied on write before the chunk is encrypted
 * (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 12; PO decision 2026-10-17, Q7: config-log details are stored with
 * secret redaction, not dropped). Each secret value becomes {@value #REDACTED} and is counted (the chunk's
 * {@code redaction_count}, and per rule in the run detail beside the rule-set id {@value #RULE_SET_ID}).
 *
 * <p>Config logs: the fields that can carry configured values are {@code before-change-preview},
 * {@code after-change-preview}, {@code path} and {@code full-path} (observed), and any {@code *-change-detail} field
 * ({@code before-change-detail} / {@code after-change-detail}: not observed; handled if one appears). Field names are
 * matched ignoring case, {@code -} and {@code _}. For those fields the rules are:</p>
 * <ol>
 *   <li>a secret-bearing {@code path}/{@code full-path} replaces the whole before/after change body with
 *       {@value #REDACTED};</li>
 *   <li>the whole inner content of a secret-named XML element is replaced (raw {@code <password>x</password>} and
 *       escaped {@code &lt;password&gt;x&lt;/password&gt;} markup alike, child elements included);</li>
 *   <li>in a path, the literal of every predicate at or below a secret-named step is replaced and the path structure
 *       kept ({@code community[@name='<redacted>']});</li>
 *   <li>{@code set}-style text: the value after a secret keyword.</li>
 * </ol>
 * Every field of both sources also gets the rules that need no config context: {@code keyword=value} /
 * {@code keyword: value}, {@code Authorization: Basic|Bearer} blobs, a long hex value after a secret word, crypt hashes
 * ({@code $1$ $5$ $6$}) and PAN encrypted values ({@code -AQ==...}).
 *
 * <p>Where a value ends: a quoted value, to its closing quote; an unquoted value inside XML text, to the end of that
 * element text; inside a tag, to the next whitespace or tag end; anywhere else, to the next whitespace or the end of
 * the field. So {@code password=ab,cd} loses all of {@code ab,cd}.</p>
 *
 * <p>Names are compared normalised: camelCase is split, {@code -}, {@code _}, {@code .} and case are ignored, so
 * {@code private_key}, {@code Pre-Shared-Key} and {@code pass_word} are secret names, and so are names that carry no
 * keyword ({@code pwd}, {@code authpwd}, {@code keytab}, {@code pkcs12} ...). Names that end in a policy-like word
 * ({@code password-policy}, {@code key-exchange}) are not. Plain prose ("password policy applied") has no separator
 * and is left alone.</p>
 *
 * <p>Idempotent: a value that already is {@value #REDACTED} is never replaced or counted, so redacting twice equals
 * redacting once, counts included. The rules are tested with synthetic text only.</p>
 */
final class PanLogRedactor {
    static final String REDACTED = "<redacted>";
    /** Versions the rules; stored with the chunk's run detail. Bump it with any rule change. */
    static final String RULE_SET_ID = "pan-config-v2";
    private static final String REDACTED_ESCAPED = "&lt;redacted&gt;";

    /** The rules, with the stable ids used as keys of {@link Result#countsByRule()}. */
    enum Rule {
        SECRET_PATH_BODY("secret_path_body"), SECRET_ELEMENT("secret_element"), PATH_PREDICATE("path_predicate"),
        KEYWORD_VALUE("keyword_value"), SECRET_ASSIGNMENT("secret_assignment"), AUTH_BLOB("auth_blob"),
        HEX_KEY("hex_key"), CRYPT_HASH("crypt_hash"), PAN_ENCRYPTED("pan_encrypted");

        private final String id;

        Rule(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    /**
     * @param redactions total replacements (the chunk's {@code redaction_count}); the sum of {@code countsByRule}
     * @param countsByRule replacements per rule id, only rules that fired; never holds text from the log
     */
    record Result(Map<String, String> fields, int redactions, String ruleSetId, Map<String, Integer> countsByRule) {
    }

    private static final Set<String> SECRET_WORDS = Set.of("password", "passwd", "passphrase", "passcode", "phash", "secret",
            "psk", "preshared", "community", "token", "privatekey", "apikey", "authkey", "sharedkey", "authenticationkey",
            "privkey", "encryptionkey", "pwd", "pkcs12", "keytab", "keydata", "keymaterial", "authcode");
    /** A name that ends in one of these describes a setting about a secret, not the secret itself. */
    private static final Set<String> BENIGN_LAST = Set.of("policy", "profile", "profiles", "complexity", "length",
            "algorithm", "lifetime", "exchange", "enabled", "mode", "type", "timeout", "expiry", "history");
    /** Path elements that hold secrets without a keyword in their name. */
    private static final Set<String> SECRET_PATH_ELEMENTS = Set.of("local-user-database", "mgt-config");
    /** Words that follow a secret keyword in ordinary sentences; never taken for a secret in a prose-prone form. */
    private static final Set<String> PROSE = Set.of("a", "an", "the", "is", "was", "are", "were", "be", "been", "not", "no",
            "has", "have", "had", "for", "of", "in", "on", "to", "at", "by", "from", "with", "and", "or", "that", "this", "it",
            "mismatch", "missing", "empty", "invalid", "wrong", "incorrect", "failed", "failure", "error", "expired",
            "required", "changed", "updated", "set", "unset", "policy", "complexity", "length", "exchange", "rotation",
            "rejected", "accepted", "denied", "verified", "verification", "found", "configured", "enabled", "disabled",
            "ok", "too", "must", "should", "cannot", "can't", "does", "did", "will", "would", "could", "format", "lifetime",
            "size", "type", "id", "mode", "negotiation", "authentication", "authorization", "signature", "validation",
            "unknown", "if", "when", "while", "after", "before", "because", "but", "as", "so");

    private static final Pattern CAMEL = Pattern.compile("([a-z0-9])([A-Z])");
    private static final String QUOTE_ALTERNATION = "'|\"|&apos;|&quot;";
    private static final Pattern QUOTED_LITERAL = Pattern.compile(
            "'(?:[^'\\\\]|\\\\.)*'|\"[^\"]*\"|&apos;.*?&apos;|&quot;.*?&quot;", Pattern.DOTALL);
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_][\\w.:-]*");
    private static final Pattern PATH_PARTS = Pattern.compile("[^A-Za-z0-9_.:-]+");
    private static final Pattern LITERAL = Pattern.compile("(?<![=+/])=\\s?(" + QUOTE_ALTERNATION + ")(.*?)\\1", Pattern.DOTALL);

    /** An element start tag, raw or escaped; group 1 name, 3 the self-closing slash. */
    private static final Pattern START_TAG = Pattern.compile(
            "(?:<|&lt;)([A-Za-z_][\\w.:-]*)((?:\\s(?:[^<>&]|&(?!gt;))*?)?)(/?)(?:>|&gt;)");
    /** {@code name = value}, {@code name: value}, {@code "name": value}. */
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?<![\\w.-])(?<name>[A-Za-z][\\w.-]*)(?<q>[\"']?)(?<sep>[ \\t]*[=:](?![=>])[ \\t]*)");
    /** {@code set}-style text: a keyword, whitespace, its value. */
    private static final Pattern KEYWORD_VALUE = Pattern.compile("(?<![\\w.-])(?<name>[A-Za-z][\\w.-]*)(?<ws>[ \\t]+)");
    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])authorization[\"']?[ \\t]*[=:][ \\t]*(?:(?:basic|bearer|negotiate)[ \\t]+)?");
    private static final Pattern AUTH_SCHEME = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(?:basic|bearer)[ \\t]+(?<v>[A-Za-z0-9+/=._~-]{8,})(?![\\w])");
    private static final Pattern HEX_AFTER_SECRET = Pattern.compile(
            "(?<![\\w.-])(?<name>[A-Za-z][\\w.-]*)(?:[ \\t]*(?:=|:|\\bis\\b|\\bwas\\b)[ \\t]*|[ \\t]+)(?:0x)?"
                    + "(?<v>[0-9A-Fa-f]{16,})(?![0-9A-Za-z])");
    private static final Pattern CRYPT_HASH = Pattern.compile(
            "(?<![A-Za-z0-9$])\\$(?:1|5|6)\\$(?:rounds=\\d+\\$)?[./A-Za-z0-9]{0,16}\\$[./A-Za-z0-9]{3,}");
    private static final Pattern PAN_ENCRYPTED = Pattern.compile("(?<![A-Za-z0-9+/])-AQ==[A-Za-z0-9+/=_-]+");

    private static final int PLAIN = 0, TEXT = 1, TAG = 2;

    private PanLogRedactor() {}

    /** @return a redacted copy of {@code fields}; {@code source} is {@code pan_system} or {@code pan_config}. */
    static Result redact(String source, Map<String, String> fields) {
        Map<String, String> out = new LinkedHashMap<>(fields);
        Map<Rule, Integer> counts = new TreeMap<>();
        boolean config = "pan_config".equals(source);
        boolean secretPath = false;
        if (config) {
            for (Map.Entry<String, String> field : out.entrySet()) {
                if (isPathField(field.getKey()) && field.getValue() != null && secretPath(field.getValue())) secretPath = true;
            }
        }
        for (Map.Entry<String, String> field : out.entrySet()) {
            String value = field.getValue();
            if (value == null || value.isEmpty()) continue;
            boolean body = config && isBodyField(field.getKey());
            boolean path = config && isPathField(field.getKey());
            if (body && secretPath) {
                if (!isPlaceholder(value.trim())) {
                    field.setValue(REDACTED);
                    hit(counts, Rule.SECRET_PATH_BODY);
                }
                continue;
            }
            if (body || path) value = secretElements(value, counts);
            if (path) value = pathPredicates(value, counts);
            if (body || path) value = keywordValues(value, counts);
            value = assignments(value, counts);
            value = authBlobs(value, counts);
            value = hexKeys(value, counts);
            value = replaceAll(CRYPT_HASH, value, Rule.CRYPT_HASH, counts);
            value = replaceAll(PAN_ENCRYPTED, value, Rule.PAN_ENCRYPTED, counts);
            field.setValue(value);
        }
        Map<String, Integer> byRule = new TreeMap<>();
        int total = 0;
        for (Map.Entry<Rule, Integer> e : counts.entrySet()) {
            byRule.put(e.getKey().id(), e.getValue());
            total += e.getValue();
        }
        return new Result(out, total, RULE_SET_ID, Map.copyOf(byRule));
    }

    private static void hit(Map<Rule, Integer> counts, Rule rule) {
        counts.merge(rule, 1, Integer::sum);
    }

    // ---- names ---------------------------------------------------------------------------------------------------

    private static String normalizedField(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_.\\s]", "");
    }

    private static boolean isPathField(String name) {
        String n = normalizedField(name);
        return n.equals("path") || n.equals("fullpath");
    }

    private static boolean isBodyField(String name) {
        String n = normalizedField(name);
        return n.equals("beforechangepreview") || n.equals("afterchangepreview") || n.endsWith("changedetail");
    }

    /** True when {@code raw} (an element, keyword or path-step name) names a secret. */
    static boolean secretName(String raw) {
        String[] parts = Arrays.stream(CAMEL.matcher(raw).replaceAll("$1-$2").toLowerCase(Locale.ROOT).split("[-_.:]+"))
                .filter(s -> !s.isEmpty()).toArray(String[]::new);
        if (parts.length == 0) return false;
        String last = parts[parts.length - 1];
        if (BENIGN_LAST.contains(last)) return false;
        String stem = last.replaceAll("\\d+$", "");
        if (stem.equals("key") || stem.equals("keys")) return true;
        String joined = String.join("", parts);
        for (String word : SECRET_WORDS) {
            if (joined.contains(word)) return true;
        }
        return false;
    }

    private static boolean secretPath(String path) {
        char[] masked = path.toCharArray();
        Matcher literal = QUOTED_LITERAL.matcher(path);
        while (literal.find()) Arrays.fill(masked, literal.start(), literal.end(), ' ');
        for (String token : PATH_PARTS.split(new String(masked))) {
            if (token.isEmpty()) continue;
            if (secretName(token) || SECRET_PATH_ELEMENTS.contains(token.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    // ---- placeholders and value extents ---------------------------------------------------------------------------

    private static boolean isPlaceholder(String text) {
        return text.equals(REDACTED) || text.equals(REDACTED_ESCAPED);
    }

    private static final String[] QUOTES = {"&quot;", "&apos;", "&#34;", "&#39;", "\"", "'"};

    private static String openingQuote(String v, int at) {
        for (String q : QUOTES) {
            if (v.startsWith(q, at)) return q;
        }
        return null;
    }

    /** True when the value at {@code at} is already {@code <redacted>}, possibly quoted, or followed by punctuation. */
    private static boolean alreadyRedacted(String v, int at) {
        String q = openingQuote(v, at);
        int p = q == null ? at : at + q.length();
        return v.startsWith(REDACTED, p) || v.startsWith(REDACTED_ESCAPED, p);
    }

    private static int closingQuote(String v, int from, String quote) {
        for (int i = from; i < v.length();) {
            if (v.charAt(i) == '\\' && quote.length() == 1 && i + 1 < v.length()) {
                i += 2;
            } else if (v.startsWith(quote, i)) {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    /** {@code v} with the placeholders blanked, so they are not mistaken for tags when the context is decided. */
    private static String shadow(String v) {
        if (!v.contains("redacted")) return v;
        StringBuilder out = new StringBuilder(v);
        for (String placeholder : new String[] {REDACTED, REDACTED_ESCAPED}) {
            for (int i = v.indexOf(placeholder); i >= 0; i = v.indexOf(placeholder, i + placeholder.length())) {
                for (int k = i; k < i + placeholder.length(); k++) out.setCharAt(k, 'X');
            }
        }
        return out.toString();
    }

    /** Where {@code idx} sits: after the end of a tag (XML text), inside a tag, or in text with no markup before it. */
    private static int context(String shadow, int idx) {
        int from = idx - 1;
        int open = Math.max(shadow.lastIndexOf('<', from), shadow.lastIndexOf("&lt;", from));
        int close = Math.max(shadow.lastIndexOf('>', from), shadow.lastIndexOf("&gt;", from));
        if (open < 0 && close < 0) return PLAIN;
        return open > close ? TAG : TEXT;
    }

    private static int nextOf(String s, int from, String... needles) {
        int best = -1;
        for (String needle : needles) {
            int i = s.indexOf(needle, from);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    private record Span(int from, int to) {
    }

    /**
     * The characters to replace for a value starting at {@code start}, or null when there is nothing to replace
     * (no value, or already redacted). Inside a tag a quoted value keeps its quotes.
     */
    private static Span valueSpan(String v, String shadow, int start, int mode) {
        if (start >= v.length() || alreadyRedacted(v, start)) return null;
        String q = openingQuote(v, start);
        if (q != null) {
            int close = closingQuote(v, start + q.length(), q);
            int innerEnd = close < 0 ? v.length() : close;
            if (mode == TAG) return innerEnd > start + q.length() ? new Span(start + q.length(), innerEnd) : null;
            return new Span(start, close < 0 ? v.length() : close + q.length());
        }
        int end;
        if (mode == TEXT) {
            end = nextOf(shadow, start, "<", "&lt;");
        } else {
            end = start;
            while (end < v.length() && !Character.isWhitespace(v.charAt(end))
                    && !(mode == TAG && (v.charAt(end) == '>' || v.startsWith("&gt;", end)))) end++;
            return end > start ? new Span(start, end) : null;
        }
        if (end < 0) end = v.length();
        while (end > start && Character.isWhitespace(v.charAt(end - 1))) end--;
        return end > start ? new Span(start, end) : null;
    }

    private static boolean isProse(String v, int at) {
        int end = at;
        while (end < v.length() && !Character.isWhitespace(v.charAt(end)) && v.charAt(end) != '<' && !v.startsWith("&lt;", end)) end++;
        String word = v.substring(at, end).toLowerCase(Locale.ROOT);
        int n = word.length();
        while (n > 0 && ",;.:)]}\"'".indexOf(word.charAt(n - 1)) >= 0) n--;
        return PROSE.contains(word.substring(0, n));
    }

    /** True when the name starting at {@code at} is the name of a tag ({@code <x}, {@code </x}, escaped alike). */
    private static boolean tagName(String v, int at) {
        return (at >= 1 && v.charAt(at - 1) == '<') || (at >= 4 && v.startsWith("&lt;", at - 4))
                || (at >= 2 && v.startsWith("</", at - 2)) || (at >= 5 && v.startsWith("&lt;/", at - 5));
    }

    // ---- rules -----------------------------------------------------------------------------------------------------

    /** Replaces the whole inner content of every secret-named element (raw or escaped markup). */
    private static String secretElements(String v, Map<Rule, Integer> counts) {
        if (v.indexOf('<') < 0 && !v.contains("&lt;")) return v;
        Matcher tag = START_TAG.matcher(v);
        StringBuilder out = null;
        int copied = 0, pos = 0;
        while (pos < v.length() && tag.find(pos)) {
            String name = tag.group(1);
            if (!secretName(name) || !tag.group(3).isEmpty()) {
                pos = tag.end();
                continue;
            }
            int contentStart = tag.end();
            int[] close = closeOf(v, contentStart, name);
            String inner = v.substring(contentStart, close[0]);
            if (!inner.isBlank() && !isPlaceholder(inner.trim())) {
                if (out == null) out = new StringBuilder();
                out.append(v, copied, contentStart).append(REDACTED);
                copied = close[0];
                hit(counts, Rule.SECRET_ELEMENT);
            }
            pos = close[1];
        }
        if (out == null) return v;
        return out.append(v, copied, v.length()).toString();
    }

    /** @return {start, end} of the matching close tag of {@code name}; both the string end when it is missing. */
    private static int[] closeOf(String v, int from, String name) {
        Pattern same = Pattern.compile("(?:<|&lt;)(/?)" + Pattern.quote(name)
                + "(?![\\w.:-])((?:\\s(?:[^<>&]|&(?!gt;))*?)?)(/?)(?:>|&gt;)");
        Matcher m = same.matcher(v);
        int depth = 1;
        int pos = from;
        while (pos < v.length() && m.find(pos)) {
            if (!m.group(1).isEmpty()) {
                if (--depth == 0) return new int[] {m.start(), m.end()};
            } else if (m.group(3).isEmpty()) {
                depth++;
            }
            pos = m.end();
        }
        return new int[] {v.length(), v.length()};
    }

    /** In a path: from the first secret-named step on, the literal of every {@code ='x'} comparison. */
    private static String pathPredicates(String v, Map<Rule, Integer> counts) {
        char[] masked = v.toCharArray();
        Matcher literal = QUOTED_LITERAL.matcher(v);
        while (literal.find()) Arrays.fill(masked, literal.start(), literal.end(), ' ');
        Matcher token = TOKEN.matcher(new String(masked));
        int anchor = -1;
        for (int pos = 0; pos < masked.length && token.find(pos); pos = token.end()) {
            if (secretName(token.group())) {
                anchor = token.start();
                break;
            }
        }
        if (anchor < 0) return v;
        Matcher eq = LITERAL.matcher(v);
        StringBuilder out = null;
        int copied = 0, pos = anchor;
        while (pos < v.length() && eq.find(pos)) {
            String body = eq.group(2);
            pos = eq.end();
            if (body.isEmpty() || isPlaceholder(body)) continue;
            if (out == null) out = new StringBuilder();
            out.append(v, copied, eq.start(2)).append(REDACTED);
            copied = eq.end(2);
            hit(counts, Rule.PATH_PREDICATE);
        }
        if (out == null) return v;
        return out.append(v, copied, v.length()).toString();
    }

    /** {@code set}-style text: the value after a secret keyword. */
    private static String keywordValues(String v, Map<Rule, Integer> counts) {
        Matcher m = KEYWORD_VALUE.matcher(v);
        String shadow = shadow(v);
        StringBuilder out = null;
        int copied = 0, pos = 0;
        while (pos < v.length() && m.find(pos)) {
            pos = m.end();
            if (tagName(v, m.start()) || !secretName(m.group("name"))) continue;
            int start = m.end();
            if (start >= v.length() || v.charAt(start) == '=' || v.charAt(start) == ':' || isProse(v, start)) continue;
            Span span = valueSpan(v, shadow, start, context(shadow, m.start()));
            if (span == null) continue;
            if (out == null) out = new StringBuilder();
            out.append(v, copied, span.from()).append(REDACTED);
            copied = pos = span.to();
            hit(counts, Rule.KEYWORD_VALUE);
        }
        if (out == null) return v;
        return out.append(v, copied, v.length()).toString();
    }

    /** {@code keyword=value}, {@code keyword: value}, {@code "keyword": "value"}. */
    private static String assignments(String v, Map<Rule, Integer> counts) {
        Matcher m = ASSIGNMENT.matcher(v);
        String shadow = shadow(v);
        StringBuilder out = null;
        int copied = 0, pos = 0;
        while (pos < v.length() && m.find(pos)) {
            pos = m.end();
            if (tagName(v, m.start("name")) || !secretName(m.group("name"))) continue;
            String sep = m.group("sep");
            if (sep.indexOf(':') >= 0 && m.group("q").isEmpty()) {
                boolean spaced = Character.isWhitespace(sep.charAt(sep.length() - 1));
                if (!spaced && m.start() > 0 && v.charAt(m.start() - 1) == '(') continue; // pam_unix(passwd:chauthtok)
                if (isProse(v, m.end())) continue;
            }
            Span span = valueSpan(v, shadow, m.end(), context(shadow, m.start()));
            if (span == null) continue;
            if (out == null) out = new StringBuilder();
            out.append(v, copied, span.from()).append(REDACTED);
            copied = pos = span.to();
            hit(counts, Rule.SECRET_ASSIGNMENT);
        }
        if (out == null) return v;
        return out.append(v, copied, v.length()).toString();
    }

    /** A base64 / JWT blob after {@code Authorization:} (any scheme given) or after {@code Basic} / {@code Bearer}. */
    private static String authBlobs(String v, Map<Rule, Integer> counts) {
        String lower = v.toLowerCase(Locale.ROOT);
        if (!lower.contains("authorization") && !lower.contains("basic") && !lower.contains("bearer")) return v;
        String shadow = shadow(v);
        StringBuilder out = null;
        int copied = 0;
        Matcher header = AUTH_HEADER.matcher(v);
        int pos = 0;
        while (pos < v.length() && header.find(pos)) {
            pos = header.end();
            if (isProse(v, header.end())) continue;
            Span span = valueSpan(v, shadow, header.end(), context(shadow, header.start()));
            if (span == null || span.to() - span.from() < 4 || span.from() < copied) continue;
            if (out == null) out = new StringBuilder();
            out.append(v, copied, span.from()).append(REDACTED);
            copied = pos = span.to();
            hit(counts, Rule.AUTH_BLOB);
        }
        String stage = out == null ? v : out.append(v, copied, v.length()).toString();
        Matcher scheme = AUTH_SCHEME.matcher(stage);
        out = null;
        copied = 0;
        pos = 0;
        while (pos < stage.length() && scheme.find(pos)) {
            pos = scheme.end();
            String blob = scheme.group("v");
            if (isPlaceholder(blob) || !blobShaped(blob)) continue;
            if (out == null) out = new StringBuilder();
            out.append(stage, copied, scheme.start("v")).append(REDACTED);
            copied = scheme.end("v");
            hit(counts, Rule.AUTH_BLOB);
        }
        if (out == null) return stage;
        return out.append(stage, copied, stage.length()).toString();
    }

    /** A bare {@code Basic x} / {@code Bearer x} is a credential only if x looks like one ("basic configuration" is not). */
    private static boolean blobShaped(String s) {
        long upper = s.chars().filter(Character::isUpperCase).count();
        boolean digit = s.chars().anyMatch(Character::isDigit);
        boolean symbol = s.chars().anyMatch(c -> "+/=._".indexOf(c) >= 0);
        return digit || symbol || s.length() >= 20 || (upper >= 2 && s.length() >= 12);
    }

    /** A long hex value after a secret word: {@code key 0123...}, {@code secret is 0123...}. */
    private static String hexKeys(String v, Map<Rule, Integer> counts) {
        Matcher m = HEX_AFTER_SECRET.matcher(v);
        StringBuilder out = null;
        int copied = 0, pos = 0;
        while (pos < v.length() && m.find(pos)) {
            pos = m.end();
            String hex = m.group("v");
            boolean letter = hex.chars().anyMatch(c -> (c | 0x20) >= 'a' && (c | 0x20) <= 'f');
            if (tagName(v, m.start("name")) || !secretName(m.group("name")) || !(letter || hex.length() >= 32)) continue;
            if (out == null) out = new StringBuilder();
            out.append(v, copied, m.start("v")).append(REDACTED);
            copied = m.end("v");
            hit(counts, Rule.HEX_KEY);
        }
        if (out == null) return v;
        return out.append(v, copied, v.length()).toString();
    }

    /** Shape rules: the whole match is the secret. */
    private static String replaceAll(Pattern pattern, String v, Rule rule, Map<Rule, Integer> counts) {
        Matcher m = pattern.matcher(v);
        StringBuilder out = null;
        int copied = 0;
        while (m.find()) {
            if (out == null) out = new StringBuilder();
            out.append(v, copied, m.start()).append(REDACTED);
            copied = m.end();
            hit(counts, rule);
        }
        if (out == null) return v;
        return out.append(v, copied, v.length()).toString();
    }

    /** Exposed for tests: the rule ids in application order. */
    static List<String> ruleIds() {
        return Arrays.stream(Rule.values()).map(Rule::id).toList();
    }
}
