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
    private static final Pattern ADDRESS = Pattern.compile("(?:" + MAC + "|" + IPV4 + "|" + IPV6 + ")");
    private static final Pattern TOKEN = Pattern.compile(MAC + "|" + IPV4 + "|" + IPV6
            + "|[\\p{L}\\p{N}\\p{M}_@%+-]+(?:[./][\\p{L}\\p{N}\\p{M}_@%+-]+)*");
    private static final Pattern INTERFACE = Pattern.compile(
            "(?:eth|bond|wrp|ens|eno|enp|wlan|lo|port)[0-9]{1,4}(?:[-.][0-9]{1,4})?");
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
            var named = identity.matcher(lines[i]);
            if (named.matches()) {
                result.append(named.group(1)).append(knownNames.containsKey(named.group(2))
                        ? knownNames.get(named.group(2)) : maskToken.apply(named.group(2)));
                continue;
            }
            var matcher = TOKEN.matcher(lines[i]);
            StringBuilder line = new StringBuilder();
            while (matcher.find()) {
                String token = matcher.group();
                boolean counter = token.matches("[0-9]+") && countPrefix.matcher(lines[i].substring(0,matcher.start())).matches();
                boolean readable = SAFE.contains(token.toLowerCase(Locale.ROOT)) || counter
                        || token.matches("[0-9]{1,5}") || INTERFACE.matcher(token).matches();
                String replacement = knownNames.containsKey(token) ? knownNames.get(token)
                        : ADDRESS.matcher(token).matches() || token.contains("@") ? maskToken.apply(token)
                        : readable ? token : maskToken.apply(token);
                matcher.appendReplacement(line, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(line);
            result.append(line);
        }
        return result.toString();
    }
    private DiagnosticText() {}
}
