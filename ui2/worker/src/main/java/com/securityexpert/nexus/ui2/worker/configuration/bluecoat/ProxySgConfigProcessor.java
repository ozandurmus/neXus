package com.securityexpert.nexus.ui2.worker.configuration.bluecoat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;

/** Sanitizes the Management Center's ProxySG configuration before indexing or presentation. */
public final class ProxySgConfigProcessor {
    public record Processed(String canonicalHash, int withheldLineCount, String sanitizedText,
            List<ConfigurationIndexEntry> index, int settingCount) {
    }

    private static final System.Logger LOG = System.getLogger(ProxySgConfigProcessor.class.getName());
    private static final java.util.concurrent.atomic.AtomicBoolean FIRST_SHAPE_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final Pattern BEGIN = Pattern.compile("^!- BEGIN (.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern END = Pattern.compile("^!- END (.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET = Pattern.compile(
            "(?i)password|passwd|secret|key|community|private-key|encrypted-password|hashed-password");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "(?i)^(?:timestamp|date|time)(?:\\s|:)|^\\d{4}-\\d{2}-\\d{2}(?:[T\\s]|$)");

    private ProxySgConfigProcessor() {
    }

    public static Processed process(String raw) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = text.split("\n");
        boolean marked = text.contains("!- BEGIN ");
        StringBuilder sanitized = new StringBuilder();
        Map<String, Integer> counts = new LinkedHashMap<>();
        String section = null;
        boolean inline = false;
        int withheld = 0;
        int settings = 0;
        List<String> shapes = new ArrayList<>();
        java.util.Set<String> markerNames = new java.util.TreeSet<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].stripTrailing();
            String t = line.strip();
            if (i < 30) shapes.add(t.replaceAll("\\p{L}", "a").replaceAll("\\p{N}", "9")
                    .replaceAll("[^a9\\s]", "."));
            Matcher begin = BEGIN.matcher(t);
            Matcher end = END.matcher(t);
            boolean secretMarker = (begin.matches() || end.matches()) && SECRET.matcher(t).find();
            if (begin.matches()) {
                section = secretMarker ? "Withheld" : title(begin.group(1));
                markerNames.add(secretMarker ? "[withheld]"
                        : begin.group(1).matches("[A-Za-z][A-Za-z -]{0,60}") ? begin.group(1) : "[other]");
            }
            if (t.equals("exit")) {
                if (!marked) section = null;
                continue;
            }
            if (t.isEmpty() || volatileHeader(t) || t.toLowerCase(Locale.ROOT).startsWith("!- connected to device")
                    || t.contains("#(config")) continue;
            if (end.matches()) {
                section = null;
                inline = false;
                if (secretMarker) withheld++;
                sanitized.append(secretMarker ? "!- END [withheld]" : line).append('\n');
                continue;
            }
            if (begin.matches()) {
                if (secretMarker) withheld++;
                sanitized.append(secretMarker ? "!- BEGIN [withheld]" : line).append('\n');
                continue;
            }
            if (!marked && !Character.isWhitespace(line.charAt(0)) && !t.startsWith("#")) {
                section = title(t.split("\\s+", 2)[0]);
            }
            if (t.toLowerCase(Locale.ROOT).startsWith("inline certificate")
                    || t.toLowerCase(Locale.ROOT).startsWith("inline keyring")) inline = true;
            boolean inlineEnd = inline && t.matches("(?i)end-\\S+-inline");
            boolean hide = inline || SECRET.matcher(t).find();
            if (hide) {
                withheld++;
                String keyword = inline ? "inline" : t.split("\\s+", 2)[0];
                if (!keyword.matches("[A-Za-z][A-Za-z-]*")) keyword = "value";
                sanitized.append(line.substring(0, line.length() - line.stripLeading().length()))
                        .append(keyword).append(" [withheld]\n");
            } else {
                sanitized.append(line).append('\n');
            }
            if (inlineEnd) inline = false;
            if (section != null && !t.startsWith("#")) {
                counts.merge(section, 1, Integer::sum);
                settings++;
            }
        }
        if (FIRST_SHAPE_LOGGED.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.INFO, "[PROXYSG] configuration shape {0}; BEGIN sections {1}", shapes, markerNames);
        }
        List<ConfigurationIndexEntry> index = new ArrayList<>();
        counts.forEach((name, count) -> index.add(new ConfigurationIndexEntry("single", name, Optional.empty(), count)));
        String out = sanitized.toString();
        try {
            String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(out.getBytes(StandardCharsets.UTF_8)));
            return new Processed(hash, withheld, out, index, settings);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean volatileHeader(String t) {
        String lower = t.toLowerCase(Locale.ROOT);
        return lower.startsWith("!- configuration date") || lower.startsWith("!- generated")
                || TIMESTAMP.matcher(t).find();
    }

    private static String title(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : name.strip().split("[-_\\s]+")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }
}
