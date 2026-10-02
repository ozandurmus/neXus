package com.securityexpert.nexus.ui2.worker.transcript;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** One bounded, append-only device conversation. */
public final class JobTranscript {
    public record Entry(long seq, String at, long elapsedMs, String channel, String kind, String text) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long LIMIT = 64L * 1024 * 1024;
    private static final String CREDENTIAL = "[credential]";
    private static final String SPARK_BACKUP = "backup settings to sftp server ";
    private final long started = System.nanoTime();
    private final List<byte[]> lines = new ArrayList<>();
    private long bytes;
    private boolean truncated;

    public synchronized void add(String channel, String kind, String text) {
        if (truncated) return;
        try {
            byte[] line = line(new Entry(lines.size() + 1L, Instant.now().toString(),
                    (System.nanoTime() - started) / 1_000_000, channel, kind, redactText(text)));
            if (bytes + line.length + 256 > LIMIT) {
                truncated = true;
                byte[] note = line(new Entry(lines.size() + 1L, Instant.now().toString(),
                        (System.nanoTime() - started) / 1_000_000, channel, "note", "transcript truncated at 64 MB"));
                lines.add(note);
                bytes += note.length;
            } else {
                lines.add(line);
                bytes += line.length;
            }
        } catch (IOException e) {
            throw new IllegalStateException("transcript encoding failed", e);
        }
    }

    public synchronized void writeTo(OutputStream sink) throws IOException {
        for (byte[] line : lines) sink.write(line);
    }

    public synchronized int size() { return lines.size(); }

    private static byte[] line(Entry entry) throws IOException {
        return (JSON.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Config exports and device errors can carry credentials beyond the ones sent in this exchange. */
    static String redactText(String text) {
        if (text == null) return "";
        return text.replaceAll("(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?(?:-----END [^-]*PRIVATE KEY-----|\\z)", CREDENTIAL)
                .replaceAll("(?im)(\\b(?:password|passwd|passphrase|secret|psksecret|private-key|encrypted-password|community|api-key|api_key|token|key-string|pre-shared-key|authentication-key|encryption-key)\\b[\\t ]+(?!\\[credential\\])[^\\r\\n]+)",
                        CREDENTIAL)
                .replaceAll("(?is)(<(?:password|passwd|passphrase|secret|private-key|api-key|key|token)(?:\\s[^>]*)?>).*?(</(?:password|passwd|passphrase|secret|private-key|api-key|key|token)>)",
                        "$1" + CREDENTIAL + "$2");
    }

    public static boolean secret(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("password") || n.equals("passwd") || n.equals("secret") || n.equals("token")
                || n.equals("key") || n.equals("api_key") || n.equals("apikey") || n.equals("passphrase")
                || n.equals("session") || n.equals("xsauth") || n.equals("formdatastr");
    }

    /** The Spark CLI carries two passwords inline; neither the command nor an echoed answer is reportable. */
    public static String safeSshCommand(String command) {
        return command != null && command.startsWith(SPARK_BACKUP)
                ? "backup settings to sftp server [receiver] filename [token] file-encryption on password [credential] "
                        + "backup-policy on username [receiver] password [credential]"
                : command;
    }

    public static String safeSshAnswer(String command, String answer) {
        return command != null && (command.startsWith(SPARK_BACKUP) || command.equals("show backup-settings-log"))
                ? lineShape(answer) : answer;
    }

    /** No words, identities, or secret bytes survive this measurement projection. */
    public static String lineShape(String value) {
        if (value == null || value.isBlank()) return "<empty>";
        String first = value.strip().split("\\R", 2)[0];
        String shape = first.replaceAll("[A-Za-z]+", "A").replaceAll("[0-9]+", "#")
                .replaceAll("[^A# ]", "?");
        return shape.length() > 160 ? shape.substring(0, 160) + "..." : shape;
    }

    public static String safeJson(String body) {
        if (body == null) return "";
        try {
            JsonNode node = JSON.readTree(body);
            redact(node);
            return JSON.writeValueAsString(node);
        } catch (IOException e) {
            return CREDENTIAL; // Malformed credential-bearing JSON must fail closed.
        }
    }

    private static void redact(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (secret(name)) object.put(name, CREDENTIAL);
                else redact(object.get(name));
            }
        } else if (node.isArray()) {
            node.forEach(JobTranscript::redact);
        }
    }

    public static String safeForm(Map<String, String> form) {
        if (form == null) return "";
        StringBuilder out = new StringBuilder();
        form.forEach((name, value) -> {
            if (out.length() > 0) out.append('&');
            out.append(java.net.URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=')
                    .append(java.net.URLEncoder.encode(secret(name) ? CREDENTIAL : value == null ? "" : value,
                            StandardCharsets.UTF_8));
        });
        return out.toString();
    }

    public static String safeForm(String body) {
        if (body == null) return "";
        StringBuilder out = new StringBuilder();
        for (String field : body.split("&", -1)) {
            if (out.length() > 0) out.append('&');
            int split = field.indexOf('=');
            String name = split < 0 ? field : field.substring(0, split);
            String decoded;
            try { decoded = java.net.URLDecoder.decode(name, StandardCharsets.UTF_8); }
            catch (IllegalArgumentException malformed) { return CREDENTIAL; }
            out.append(name).append('=');
            out.append(secret(decoded) ? java.net.URLEncoder.encode(CREDENTIAL, StandardCharsets.UTF_8)
                    : split < 0 ? "" : field.substring(split + 1));
        }
        return out.toString();
    }

    public static String safePath(String path) {
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            int start = scheme + 3;
            int authorityEnd = path.indexOf('/', start);
            if (authorityEnd < 0) authorityEnd = path.length();
            int at = path.indexOf('@', start);
            if (at >= 0 && at < authorityEnd)
                path = path.substring(0, start) + CREDENTIAL + path.substring(at);
        }
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query + 1) + safeForm(path.substring(query + 1));
    }

    public static String safeHeaders(Map<String, ? extends List<String>> headers) {
        StringBuilder out = new StringBuilder();
        headers.forEach((name, values) -> {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.contains("authorization") || n.contains("cookie") || n.contains("csrf")
                    || n.contains("token") || n.contains("session") || n.contains("secret")
                    || n.contains("api-key") || n.contains("api_key") || (n.startsWith("x-") && n.contains("key"))
                    || n.equals("x-auth") || secret(n)) return;
            out.append(name).append(": ").append(n.equals("location")
                    ? values.stream().map(JobTranscript::safePath).collect(java.util.stream.Collectors.joining(", "))
                    : String.join(", ", values)).append('\n');
        });
        return out.toString();
    }

    /** Session credentials returned by a login become credentials neXus sends on later requests. */
    public static String safeResponseBody(String contentType, String body) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json")) {
            try {
                JsonNode node = JSON.readTree(body);
                redact(node);
                return JSON.writeValueAsString(node);
            } catch (IOException ignored) {
                return CREDENTIAL;
            }
        }
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("html")) {
            java.util.regex.Matcher tags = java.util.regex.Pattern.compile("(?i)<input\\b[^>]*>").matcher(body);
            StringBuilder safe = new StringBuilder();
            while (tags.find()) {
                String tag = tags.group();
                if (tag.matches("(?is).*\\bname\\s*=\\s*['\"](?:xsauth|FormDataStr)['\"].*"))
                    tags.appendReplacement(safe, java.util.regex.Matcher.quoteReplacement(tag.replaceAll(
                            "(?i)(\\bvalue\\s*=\\s*['\"])[^'\"]*", "$1" + CREDENTIAL)));
            }
            tags.appendTail(safe);
            return safe.toString();
        }
        return body;
    }

    /** Remove any credential values sent in this exchange if a device echoes them. */
    public static String withoutSentSecrets(String response, Map<String, String> sent) {
        if (sent == null) return response;
        String safe = response;
        for (Map.Entry<String, String> field : sent.entrySet()) {
            if (secret(field.getKey()) && field.getValue() != null && !field.getValue().isEmpty())
                safe = safe.replace(field.getValue(), CREDENTIAL);
        }
        return safe;
    }

    public static String withoutSentSecrets(String response, String sentBody, String contentType) {
        if (sentBody == null) return response;
        try {
            if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json")) {
                List<String> values = new ArrayList<>();
                collectSentSecrets(JSON.readTree(sentBody), values);
                String safe = response;
                for (String value : values) if (!value.isEmpty()) safe = safe.replace(value, CREDENTIAL);
                return safe;
            }
            if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("form")) {
                String safe = response;
                for (String pair : sentBody.split("&")) {
                    String[] parts = pair.split("=", 2);
                    if (parts.length == 2 && secret(java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8))) {
                        String value = java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                        if (!value.isEmpty()) safe = safe.replace(value, CREDENTIAL);
                    }
                }
                return safe;
            }
        } catch (IOException | IllegalArgumentException ignored) {
            return CREDENTIAL;
        }
        return response;
    }

    private static void collectSentSecrets(JsonNode node, List<String> values) {
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                if (secret(field.getKey())) values.add(field.getValue().asText());
                else collectSentSecrets(field.getValue(), values);
            });
        } else if (node.isArray()) node.forEach(child -> collectSentSecrets(child, values));
    }

}
