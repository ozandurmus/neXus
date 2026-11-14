package com.securityexpert.nexus.ui2.platform;

import java.util.Set;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.function.Function;

/** Bounded diagnostic output; unknown tokens are never passed through to AIView. */
public final class DiagnosticText {
    public static final int MAX_BYTES = 262_144;
    private static final Pattern SECRET = Pattern.compile("(?i)\\b(password|passwd|secret|token|key|api[_-]?key|community|psk|enc|encrypted|passphrase|authorization|credential)\\b");
    private static final String MAC = "(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}|(?:[0-9A-Fa-f]{4}\\.){2}[0-9A-Fa-f]{4}";
    private static final String IPV4 = "[0-9]{1,3}(?:\\.[0-9]{1,3}){3}(?:/[0-9]{1,2})?";
    private static final String IPV6 = "(?:[0-9A-Fa-f]{0,4}:){2,}[0-9A-Fa-f:.]*(?:%[A-Za-z0-9_.-]+)?(?:/[0-9]{1,3})?";
    private static final Pattern TIME_OF_DAY = Pattern.compile(
            "(?:[01]?[0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,9})?");
    private static final Pattern ADDRESS = Pattern.compile("(?:" + MAC + "|" + IPV4 + "|" + IPV6 + ")");
    private static final Pattern TOKEN = Pattern.compile(MAC + "|" + IPV4 + "|" + IPV6
            + "|[\\p{L}\\p{N}\\p{M}_@%+-]+(?:\\.[\\p{L}\\p{N}\\p{M}_@%+-]+)*");
    private static final Pattern INTERFACE = Pattern.compile(
            "(?:eth|bond|wrp|ens|eno|enp|wlan|lo|port)[0-9]{1,4}(?:[-.][0-9]{1,4})?");
    private static final Pattern SIZE = Pattern.compile(
            "(?i)(?:[0-9]{1,5}(?:\\.[0-9]{1,4})?[BKMGTPE]"
            + "|[0-9]{1,6}(?:\\.[0-9]{1,4})?(?:[KMGTPE]i?B|[KMGT]?bps|[KM]?pps|[kMG]?Hz|ms|us|ns|secs?|mins?|hrs?)"
            + "|[0-9]{1,3}(?:\\.[0-9]{1,4})?%"
            + "|[0-9]{1,5}\\.[0-9]{1,4})");
    private static final Pattern DATE = Pattern.compile(
            "(?i)(?:(?:19|20)[0-9]{2}-[01][0-9]-[0-3][0-9]"
            + "|[0-3]?[0-9]-(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)-(?:19|20)[0-9]{2})");
    private static final Pattern CP_LOG_FILE = Pattern.compile(
            "(?i)(?:19|20)[0-9]{2}-[01][0-9]-[0-3][0-9]_[0-9]{6}(?:_[0-9]+)?\\.(?:log|logptr|logaccount_ptr|adtlog|elg)(?:\\.[0-9]+)?(?:\\.gz)?");
    private static final Pattern FILE_MODE = Pattern.compile("[-dlcbps][rwxstST-]{9}[.+]?");
    private static final Pattern PSEUDONYM = Pattern.compile(
            "(?:SN-[0-9A-F]{10}|(?:FW|CLS|VS|DOM)-[A-Z]+-[0-9]{2}(?:-(?:M[0-9]{1,2}|[0-9]{2}))?)");
    private static final Pattern INLINE_IDENTITY_PREFIX = Pattern.compile(
            "(?i).*(?:\\b(?:user[-_]?name|user|account|login|owner|serial(?:[-_ ]?number)?|sn|host[-_]?name|peer[-_]?name)\\s*(?:[:=]\\s*['\"]?|['\"])"
            + "|\\bfor\\s+(?:invalid\\s+)?user\\s+['\"]?)$");
    private static final Set<String> SAFE_EXTENSIONS = Set.of(
            "log", "logptr", "logaccount_ptr", "adtlog", "elg", "ptr", "cpbak",
            "conf", "cfg", "csv", "txt", "json", "xml", "yaml", "yml",
            "gz", "tgz", "tar", "bz2", "xz", "zip", "bak", "old", "orig",
            "swp", "lock", "pid", "sock", "out", "err", "sh");
    private static final Set<String> SAFE = loadVocabulary();

    private static Set<String> loadVocabulary() {
        try (var stream = DiagnosticText.class.getResourceAsStream("/diagnostic-safe-words.txt")) {
            if (stream == null) throw new IllegalStateException("Diagnostic vocabulary is missing");
            return Set.copyOf(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .lines().filter(word -> !word.isBlank()).toList());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Diagnostic vocabulary is unreadable", failure);
        }
    }
    public static String scrubSecrets(String output) {
        if (output == null) return "";
        StringBuilder result = new StringBuilder();
        boolean privateBlock = false;
        for (String line : output.split("\\R", -1)) {
            if (line.contains("-----BEGIN ") && line.contains("PRIVATE KEY")) privateBlock = true;
            boolean hidden = privateBlock || SECRET.matcher(line).find();
            if (line.contains("-----END ") && line.contains("PRIVATE KEY")) privateBlock = false;
            if (result.length() > 0) result.append('\n');
            result.append(hidden ? "[SECRET REDACTED]" : line.replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]", ""));
        }
        return result.toString();
    }
    public static String masked(String text, Function<String, String> maskToken) {
        return masked(text, java.util.Map.of(), maskToken);
    }

    public static String masked(String text, java.util.Map<String, String> knownNames, Function<String, String> maskToken) {
        var identity = Pattern.compile("(?i)^(\\s*(?:host[- ]?name|serial(?:[- ]?number)?|user(?:[- ]?name)?|account|name)\\s*[:=]\\s*)(.*)$");
        var countPrefix = Pattern.compile("(?i).*(?:mtu|metric|packets|bytes|errors|dropped|overruns|collisions|txqueuelen|speed)\\s*[:=]?\\s*$");
        StringBuilder result = new StringBuilder();
        String[] lines = scrubSecrets(text).split("\\R", -1);
        for (int i=0; i<lines.length; i++) {
            if (i>0) result.append('\n');
            if (lines[i].equals("[SECRET REDACTED]")) {
                result.append(lines[i]);
                continue;
            }
            var named = identity.matcher(lines[i]);
            if (named.matches()) {
                String value = named.group(2);
                String known = lookupKnownName(knownNames, value);
                result.append(named.group(1)).append(known != null ? known : maskToken.apply(value));
                continue;
            }
            var matcher = TOKEN.matcher(lines[i]);
            StringBuilder line = new StringBuilder();
            while (matcher.find()) {
                String token = matcher.group();
                String prefix = lines[i].substring(0, matcher.start());
                boolean inlineIdentity = INLINE_IDENTITY_PREFIX.matcher(prefix).matches();
                boolean timeOfDay = TIME_OF_DAY.matcher(token).matches();
                boolean address = !timeOfDay && ADDRESS.matcher(token).matches();
                boolean counter = token.matches("[0-9]+") && countPrefix.matcher(prefix).matches();
                boolean readable = !inlineIdentity && isReadableToken(token, counter, timeOfDay);
                String known = lookupKnownName(knownNames, token);
                String replacement = known != null ? known
                        : address || token.contains("@") || inlineIdentity ? maskToken.apply(token)
                        : readable ? token : maskToken.apply(token);
                matcher.appendReplacement(line, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(line);
            result.append(line);
        }
        return result.toString();
    }

    private static String lookupKnownName(java.util.Map<String, String> knownNames, String token) {
        if (knownNames == null || knownNames.isEmpty()) return null;
        String exact = knownNames.get(token);
        if (exact != null) return exact;
        for (var entry : knownNames.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(token)) return entry.getValue();
        }
        return null;
    }

    private static boolean isReadableToken(String token, boolean counter, boolean timeOfDay) {
        String lower = token.toLowerCase(Locale.ROOT);
        return SAFE.contains(lower)
                || counter
                || timeOfDay
                || token.matches("[0-9]{1,5}")
                || SIZE.matcher(token).matches()
                || DATE.matcher(token).matches()
                || CP_LOG_FILE.matcher(token).matches()
                || FILE_MODE.matcher(token).matches()
                || INTERFACE.matcher(token).matches()
                || PSEUDONYM.matcher(token).matches()
                || isSafeFileName(lower)
                || isSafeUnderscoreCompound(lower)
                || !hasLetterOrDigit(token);
    }

    private static boolean isSafeFileName(String lower) {
        int dot = lower.indexOf('.');
        if (dot <= 0 || dot == lower.length() - 1) return false;
        String[] parts = lower.split("\\.");
        if (parts.length < 2) return false;
        if (!SAFE.contains(parts[0]) && !isSafeUnderscoreCompound(parts[0])) return false;
        boolean hasSafeExtension = false;
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (SAFE_EXTENSIONS.contains(part)) {
                hasSafeExtension = true;
            } else if (!part.matches("[0-9]{1,2}")) {
                return false;
            }
        }
        return hasSafeExtension || (parts.length == 2 && parts[1].matches("[0-9]{1,2}"));
    }

    private static boolean isSafeUnderscoreCompound(String lower) {
        if (lower.indexOf('_') <= 0) return false;
        String[] parts = lower.split("_");
        if (parts.length < 2) return false;
        for (String part : parts) {
            if (part.isEmpty() || !part.matches("[a-z]+") || !SAFE.contains(part)) return false;
        }
        return true;
    }

    private static boolean hasLetterOrDigit(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (Character.isLetterOrDigit(token.charAt(i))) return true;
        }
        return false;
    }

    private DiagnosticText() {}
}
