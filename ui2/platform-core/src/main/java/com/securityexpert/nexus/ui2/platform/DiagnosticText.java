package com.securityexpert.nexus.ui2.platform;

import java.util.Set;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.function.Function;

/** Bounded diagnostic output; unknown tokens are never passed through to AIView. */
public final class DiagnosticText {
    public static final int MAX_BYTES = 65_536;
    private static final Pattern SECRET = Pattern.compile("(?i)(password|passwd|secret|token|private.key|community|authorization|api.key)");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}\\p{M}_./@%+-]+");
    private static final Set<String> SAFE = Set.of("status", "up", "down", "auto", "speed", "duplex", "full", "half",
        "link", "encap", "ethernet", "inet", "inet6", "addr", "address", "mask", "bcast", "hwaddr", "mtu", "metric",
        "rx", "tx", "packets", "bytes", "errors", "dropped", "overruns", "frame", "carrier", "collisions", "txqueuelen",
        "broadcast", "running", "multicast", "loopback", "scope", "global", "host", "interface", "name", "hostname",
        "serial", "number", "version", "system", "mode", "active", "standby", "enabled", "disabled", "unknown",
        "connected", "disconnected", "route", "gateway", "destination", "network", "device", "flags", "ip", "netmask");
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
        var identity = Pattern.compile("(?i)^(\\s*(?:hostname|serial(?:[- ]?number)?|username|user|account|name)\\s*[:=]\\s*)(.*)$");
        var countPrefix = Pattern.compile("(?i).*(?:mtu|metric|packets|bytes|errors|dropped|overruns|collisions|txqueuelen|speed)\\s*[:=]?\\s*$");
        StringBuilder result = new StringBuilder();
        String[] lines = scrubSecrets(text).split("\\R", -1);
        for (int i=0; i<lines.length; i++) {
            if (i>0) result.append('\n');
            var named = identity.matcher(lines[i]);
            if (named.matches()) {
                result.append(named.group(1)).append(maskToken.apply(named.group(2)));
                continue;
            }
            var matcher = TOKEN.matcher(lines[i]);
            StringBuilder line = new StringBuilder();
            while (matcher.find()) {
                String token = matcher.group();
                boolean counter = token.matches("[0-9]+") && countPrefix.matcher(lines[i].substring(0,matcher.start())).matches();
                String replacement = SAFE.contains(token.toLowerCase(Locale.ROOT)) || counter ? token : maskToken.apply(token);
                matcher.appendReplacement(line, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(line);
            result.append(line);
        }
        return result.toString();
    }
    private DiagnosticText() {}
}
