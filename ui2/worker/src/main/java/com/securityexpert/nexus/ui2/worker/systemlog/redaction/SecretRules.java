package com.securityexpert.nexus.ui2.worker.systemlog.redaction;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The patterns of rule set {@link LogRedactor#RULE_SET_ID}. Every rule names the group (or groups) that hold the
 * secret VALUE; nothing else on the line is touched. Rules never need a vendor line format, only the shape of the
 * secret-bearing phrase, so they hold for messages, elg and csv lines alike.
 *
 * <p>Two kinds of rule exist and the difference is the whole over-redaction policy: <em>exact</em> forms
 * ({@code name=value}, command syntax such as {@code set user X password VALUE}, a hash or blob shape) redact
 * whatever follows; <em>prose-prone</em> forms ({@code name: value}, {@code radius ... key VALUE}, {@code auth-key
 * VALUE}) skip a value that is an ordinary English word ({@link #PROSE}), so "key: format invalid" and
 * "radius secret is wrong" stay readable.</p>
 */
final class SecretRules {

    static final String REDACTED = "<redacted>";

    /** What a rule decides about one match, before the value is replaced. */
    @FunctionalInterface
    interface Check {
        boolean accept(Matcher match, String value);
    }

    /**
     * @param groups names of the groups that hold the values, in order of position in the match
     * @param raw the group is replaced as it stands (no quote or trailing-punctuation handling)
     */
    record Rule(RuleId id, Pattern pattern, List<String> groups, Check check, boolean raw) {
    }

    // ---- value shapes ----------------------------------------------------------------------------------------

    private static final String QUOTED = "\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|&quot;(?:(?!&quot;).)*&quot;";
    /** A bare token; stops before {@code &name=} so a URL query's next parameter is not swallowed. */
    private static final String BARE = "(?:(?!&[A-Za-z_][\\w.-]*=)\\S)+";
    private static final String VALUE = "(?:" + QUOTED + "|" + BARE + ")";

    private static final String CORE = "pass[_-]?(?:word|wd|phrase)|pwd|secret|psk|community|token|key|phash|authpwd|privpwd";

    /** Words that follow a secret keyword in ordinary sentences; never taken for a secret in a prose-prone form. */
    static final Set<String> PROSE = Set.of("a", "an", "the", "is", "was", "are", "were", "be", "been", "not", "no", "has",
            "have", "had", "for", "of", "in", "on", "to", "at", "by", "from", "with", "and", "or", "that", "this", "it",
            "mismatch", "missing", "empty", "invalid", "wrong", "incorrect", "failed", "failure", "error", "expired",
            "required", "changed", "updated", "set", "unset", "policy", "complexity", "length", "exchange", "rotation",
            "rejected", "accepted", "denied", "verified", "verification", "found", "configured", "enabled", "disabled",
            "ok", "too", "must", "should", "cannot", "can't", "does", "did", "will", "would", "could", "format",
            "lifetime", "size", "type", "id", "ring", "store", "file", "pair", "usage", "mode", "negotiation",
            "authentication", "authorization", "read-only", "read-write", "ro", "rw", "string", "version", "v1", "v2",
            "v2c", "v3", "unknown", "if", "when", "while", "after", "before", "because", "but", "as", "so");

    /** Values that mean "no secret here". */
    private static final Set<String> PLACEHOLDERS = Set.of("null", "(null)", "none", "(none)", "n/a", "empty", "nil");

    // ---- checks ----------------------------------------------------------------------------------------------

    private static final Check ALWAYS = (m, v) -> true;
    private static final Check NOT_PROSE = (m, v) -> !isProse(v);

    static boolean isProse(String value) {
        String word = value.toLowerCase(Locale.ROOT);
        int end = word.length();
        while (end > 0 && ",;.:)]}\"'".indexOf(word.charAt(end - 1)) >= 0) {
            end--;
        }
        return PROSE.contains(word.substring(0, end));
    }

    static boolean isPlaceholder(String value) {
        return value.isEmpty() || value.equals(REDACTED) || value.equals("&lt;redacted&gt;")
                || PLACEHOLDERS.contains(value.toLowerCase(Locale.ROOT));
    }

    /** {@code name: value} needs a space after the colon unless the name is quoted (JSON), and skips prose. */
    private static final Check ASSIGNMENT = (m, v) -> {
        String sep = m.group("sep");
        if (sep.indexOf(':') < 0) {
            return true;
        }
        boolean quotedName = !m.group("nq").isEmpty();
        boolean spaceAfterColon = Character.isWhitespace(sep.charAt(sep.length() - 1));
        if (!quotedName && !spaceAfterColon) {
            return false; // "pam_unix(passwd:chauthtok)"
        }
        return quotedName || !isProse(v);
    };

    private static final Check HASH_SHAPED = (m, v) -> v.indexOf('$') >= 0
            || (v.length() >= 13 && v.chars().anyMatch(Character::isDigit) && !isProse(v));

    private static final Check BLOB_SHAPED = (m, v) -> {
        if (v.startsWith("/") || v.length() < 16) {
            return false;
        }
        long dots = v.chars().filter(c -> c == '.').count();
        return dots >= 2 || v.chars().anyMatch(c -> Character.isDigit(c) || c == '+' || c == '=');
    };

    private static final Check HEX_SHAPED = (m, v) -> {
        boolean letter = v.chars().anyMatch(c -> (c | 0x20) >= 'a' && (c | 0x20) <= 'f');
        return letter || v.length() >= 32;
    };

    private static final Check CRYPT_SHAPED = (m, v) -> v.chars().filter(c -> c == '$').count() >= 3;

    private static final Check NOT_A_FLAG = (m, v) -> !v.startsWith("--");

    private static final Check COMMUNITY_VALUE = NOT_PROSE;

    // ---- the rules, in application order ---------------------------------------------------------------------

    private static final int CI = Pattern.CASE_INSENSITIVE;

    private static Rule rule(RuleId id, String regex, Check check, String... groups) {
        return new Rule(id, Pattern.compile(regex, CI), List.of(groups), check, false);
    }

    static final List<Rule> RULES = List.of(
            rule(RuleId.URL_CREDENTIALS,
                    "\\b[a-z][a-z0-9+.-]{1,15}://[^\\s/:@\"']*:(?<v>[^\\s/@\"']+)@", ALWAYS, "v"),

            rule(RuleId.GAIA_USER_PASSWORD,
                    "\\b(?:(?:set|add)\\s+user\\s+\\S+\\s+(?:password|passwd)(?:-hash)?|set\\s+expert-password)\\s+(?<v>" + VALUE + ")",
                    ALWAYS, "v"),
            rule(RuleId.GAIA_PASSWORD_HASH,
                    "(?<![A-Za-z0-9])(?:[A-Za-z0-9]+-)*password-hash(?:\\s*[=:]\\s*|\\s+)(?<v>" + VALUE + ")",
                    HASH_SHAPED, "v"),

            rule(RuleId.SNMP_COMMUNITY,
                    "\\b(?:set|add|delete)\\s+snmp\\b[^\\n]*?\\bcommunity\\s+(?<v>" + VALUE + ")", COMMUNITY_VALUE, "v"),
            rule(RuleId.SNMP_COMMUNITY,
                    "(?<![A-Za-z0-9])(?:ro|rw|trap)community6?\\s+(?<v>" + VALUE + ")", ALWAYS, "v"),
            rule(RuleId.SNMP_COMMUNITY,
                    "\\bcom2sec6?\\s+\\S+\\s+\\S+\\s+(?<v>" + VALUE + ")", ALWAYS, "v"),
            rule(RuleId.SNMP_COMMUNITY,
                    "\\b(?:trapsink|trap2sink|informsink)\\s+\\S+\\s+(?<v>" + VALUE + ")", ALWAYS, "v"),

            rule(RuleId.SNMP_USM,
                    "(?<![A-Za-z0-9])(?:auth|authentication|priv|privacy)[_-]?(?:key|pass(?:[_-]?(?:word|wd|phrase))?)"
                            + "(?:[_-]?hashed)?(?:\\s*[=:]\\s*|\\s+)(?<v>" + VALUE + ")",
                    NOT_PROSE, "v"),
            rule(RuleId.SNMP_USM,
                    "\\bcreateUser\\s+\\S+\\s+(?:MD5|SHA[\\w-]*)\\s+(?<v>\"[^\"]*\"|\\S+)"
                            + "(?:\\s+(?:DES|AES[\\w-]*)(?:\\s+(?<w>\"[^\"]*\"|\\S+))?)?",
                    ALWAYS, "v", "w"),

            rule(RuleId.AAA_SECRET,
                    "\\b(?:add|set)\\s+aaa\\s+(?:radius|tacacs)-servers\\b[^\\n]*?\\b(?:key|secret)\\s+(?<v>" + VALUE + ")",
                    ALWAYS, "v"),
            rule(RuleId.AAA_SECRET,
                    "(?<![A-Za-z0-9])(?:radius|tacacs\\+?|tacplus)[^\\n]*?(?<![A-Za-z0-9])(?:shared[_ -]?)?(?:secret|key)"
                            + "(?:\\s*[=:]\\s*|\\s+)(?<v>" + VALUE + ")",
                    NOT_PROSE, "v"),

            rule(RuleId.CLI_FLAG,
                    "(?<![A-Za-z0-9-])--(?:password|passwd|pass|secret|token|psk|community|passphrase|api-key|apikey|auth-key|priv-key)"
                            + "(?:=|\\s+)(?<v>" + VALUE + ")",
                    NOT_A_FLAG, "v"),

            rule(RuleId.SECRET_ASSIGNMENT,
                    "(?<![A-Za-z0-9])(?:[A-Za-z0-9]+[_.-])*(?:" + CORE + ")(?:[_-]?(?:hash|hashed|string|str))?"
                            + "(?<nq>[\"']?)(?<sep>\\s*[=:](?![=>])\\s*)(?<v>" + VALUE + ")",
                    ASSIGNMENT, "v"),

            rule(RuleId.AUTH_BLOB,
                    "(?<![A-Za-z0-9])(?:authorization|basic|bearer|negotiate|token|auth|credentials?)(?:\\s*[=:]\\s*|\\s+)"
                            + "(?:(?:basic|bearer|negotiate|token)\\s+)?"
                            + "(?<v>[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}|[A-Za-z0-9+/_-]{16,}={0,2})",
                    BLOB_SHAPED, "v"),

            rule(RuleId.HEX_KEY,
                    "(?<![A-Za-z0-9])(?:[A-Za-z0-9]+[_-])*(?:key|secret|psk|pass[_-]?(?:word|wd|phrase)|token)"
                            + "(?:\\s*(?:=|:|\\bis\\b|\\bwas\\b)\\s*|\\s+)(?:0x)?(?<v>[0-9a-f]{16,})(?![0-9A-Za-z])",
                    HEX_SHAPED, "v"),

            rule(RuleId.CRYPT_HASH,
                    "(?<![A-Za-z0-9$])(?<v>\\$(?:1|2[abxy]|5|6|y|7)\\$[./A-Za-z0-9,=$-]*[./A-Za-z0-9]{8,})",
                    CRYPT_SHAPED, "v"),

            new Rule(RuleId.PEM_PRIVATE_KEY,
                    Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----(?<v>.+?)-----END [A-Z0-9 ]*PRIVATE KEY-----"),
                    List.of("v"), ALWAYS, true));

    /** Lines without any of these cannot match a rule above; the scan is the fast path for ordinary lines. */
    static final Pattern TRIGGER = Pattern.compile(
            "pass|pwd|secret|key|community|token|psk|auth|bearer|basic|usm|createuser|sink|com2sec|negotiate|credential"
                    + "|phash|://|-----|\\$[0-9a-z]{1,2}\\$",
            CI);

    // ---- PEM private key blocks (stateful across lines of one chunk) --------------------------------------------

    static final Pattern PEM_BEGIN = Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----");
    static final Pattern PEM_ANY_BEGIN = Pattern.compile("-----BEGIN [A-Z0-9 ]+-----");
    static final Pattern PEM_ANY_END = Pattern.compile("-----END [A-Z0-9 ]+-----");
    static final Pattern PEM_END = Pattern.compile("-----END [A-Z0-9 ]*PRIVATE KEY-----");
    /** A whole line that is nothing but a long base64 run: key material that lost its BEGIN line to a chunk edge. */
    static final Pattern BASE64_LINE = Pattern.compile("[ \\t]*[A-Za-z0-9+/]{48,}={0,2}[ \\t]*");
    static final Pattern BASE64_ONLY = Pattern.compile("[A-Za-z0-9+/=]+");
    static final Pattern BASE64_TAIL = Pattern.compile("[A-Za-z0-9+/=]{16,}$");

    private SecretRules() {
    }
}
