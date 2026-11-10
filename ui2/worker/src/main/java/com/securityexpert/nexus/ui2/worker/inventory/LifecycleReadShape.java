package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.worker.inventory.LifecycleReadSwitch.Kind;

/**
 * Measure-first reduction of a lifecycle read's answer (group A) to a masked "shape" that is safe to log: the structure
 * of the answer with no customer value in it. The answer itself is never kept (Raw-evidence law).
 *
 * <p>CLI text: line by line, split on whitespace into tokens; inside a token the characters {@code : = , ( ) | [ ]} are
 * separators kept verbatim. A token piece is emitted verbatim only when its lower-case form is in that read kind's
 * hard-coded keyword list (generic vendor words only); otherwise digits-only -> {@code 9}, letters-only ->
 * {@code a<len>}, anything else -> {@code m<len>}. {@code CK-...} -> {@code CK-m<len>}; {@code sk} + digits -> {@code sk9}.
 * Identical masked lines are collapsed to one with an {@code xN} count.</p>
 *
 * <p>JSON: only field paths, JSON types and array lengths, no value at all.</p>
 */
public final class LifecycleReadShape {

    private static final System.Logger LOG = System.getLogger(LifecycleReadShape.class.getName());

    static final int MAX_LINES = 60;
    static final int MAX_LINE_CHARS = 240;
    static final int MAX_TOTAL_CHARS = 6000;
    static final String LINE_SEPARATOR = " // ";
    private static final String SEPARATORS = ":=,()|[]";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern SK_DIGITS = Pattern.compile("(?i)sk\\d+");

    private static final Set<String> MONTHS = Set.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "sept",
            "oct", "nov", "dec");

    private static final Set<String> CPLIC_WORDS = union(MONTHS, Set.of("host", "expiration", "features", "signature", "never",
            "contract", "coverage", "id", "sku", "license", "licenses", "installed", "no", "type", "central", "local",
            "expired", "valid", "status", "name", "date", "blade", "blades", "product", "none", "permanent", "evaluation",
            "trial", "subscription", "support"));

    private static final Set<String> CPLP_WORDS = Set.of("id", "patch", "proc", "status", "mode", "pids", "installed", "comment",
            "armed", "ready", "reverted", "jumbofix", "unsupp", "nopatch", "nolib", "auto", "manual", "not", "found", "command",
            "no", "patches", "known");

    private static final Set<String> ASA_WORDS = union(MONTHS, Set.of("smart", "licensing", "status", "is", "enabled", "disabled",
            "registration", "registered", "unregistered", "account", "virtual", "export-controlled", "functionality", "allowed",
            "initial", "last", "renewal", "attempt", "next", "expires", "license", "authorization", "authorized", "out", "of",
            "compliance", "eval", "evaluation", "mode", "period", "expired", "communication", "deadline", "utility", "data",
            "privacy", "sending", "hostname", "yes", "no", "callhome", "version", "transport", "type", "usage", "product",
            "information", "udi", "pid", "sn", "agent", "for", "overall", "licensed", "entitlement", "entitlements", "serial",
            "number", "features", "feature", "tag", "count", "description", "enforcement", "not", "applicable", "in", "standby",
            "state", "succeeded", "failed", "on", "platform", "this", "has", "context", "contexts", "perpetual", "tier",
            "strong", "encryption", "base", "standard", "time", "and", "to", "the", "a", "or", "none", "active", "unlimited"));

    private static final Map<Kind, Set<String>> ALLOW = new EnumMap<>(Kind.class);

    static {
        ALLOW.put(Kind.CP_CPLIC_PRINT, CPLIC_WORDS);
        ALLOW.put(Kind.CP_SPARK_SHOW_LICENSE, CPLIC_WORDS);
        ALLOW.put(Kind.CP_CPLP_LIST, CPLP_WORDS);
        ALLOW.put(Kind.ASA_SHOW_LICENSE_ALL, ASA_WORDS);
    }

    private LifecycleReadShape() {
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        java.util.HashSet<String> all = new java.util.HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }

    /** The keyword list of a CLI read kind (empty for the JSON kinds). */
    static Set<String> allowList(Kind kind) {
        return ALLOW.getOrDefault(kind, Set.of());
    }

    // ------------------------------------------------------------------------------------------ CLI text

    /** The masked shape of a CLI answer. Never contains a value that is not a listed keyword of {@code kind}. */
    public static String cliShape(Kind kind, String output) {
        Set<String> allow = allowList(kind);
        Map<String, Integer> distinct = new LinkedHashMap<>();
        boolean tooManyLines = false;
        if (output != null) {
            for (String raw : output.split("\\R")) {
                String line = raw.strip();
                if (line.isEmpty()) {
                    continue;
                }
                String masked = maskLine(allow, line);
                if (!distinct.containsKey(masked) && distinct.size() >= MAX_LINES) {
                    tooManyLines = true;
                    continue;
                }
                distinct.merge(masked, 1, Integer::sum);
            }
        }
        StringBuilder out = new StringBuilder();
        boolean tooLong = false;
        int emitted = 0;
        for (Map.Entry<String, Integer> entry : distinct.entrySet()) {
            String part = entry.getKey() + (entry.getValue() > 1 ? " x" + entry.getValue() : "");
            if (part.length() > MAX_LINE_CHARS) {
                part = part.substring(0, MAX_LINE_CHARS) + "~";
            }
            int add = (out.length() == 0 ? 0 : LINE_SEPARATOR.length()) + part.length();
            if (out.length() + add > MAX_TOTAL_CHARS) {
                tooLong = true;
                break;
            }
            if (out.length() > 0) {
                out.append(LINE_SEPARATOR);
            }
            out.append(part);
            emitted++;
        }
        if (tooManyLines || tooLong) {
            out.append(" [truncated:").append(tooLong ? "chars" : "lines").append(" shown=").append(emitted)
                    .append(" distinct>=").append(distinct.size()).append(']');
        }
        return out.length() == 0 ? "-" : out.toString();
    }

    static String maskLine(Set<String> allow, String line) {
        StringBuilder out = new StringBuilder();
        for (String token : WHITESPACE.split(line)) {
            if (token.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            maskToken(allow, token, out);
        }
        return out.toString();
    }

    private static void maskToken(Set<String> allow, String token, StringBuilder out) {
        StringBuilder piece = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (SEPARATORS.indexOf(c) >= 0) {
                maskPiece(allow, piece.toString(), out);
                piece.setLength(0);
                out.append(c);
            } else {
                piece.append(c);
            }
        }
        maskPiece(allow, piece.toString(), out);
    }

    private static void maskPiece(Set<String> allow, String piece, StringBuilder out) {
        if (piece.isEmpty()) {
            return;
        }
        if (allow.contains(piece.toLowerCase(Locale.ROOT))) {
            out.append(piece);
        } else if (piece.regionMatches(true, 0, "CK-", 0, 3)) {
            out.append("CK-m").append(piece.length() - 3);
        } else if (SK_DIGITS.matcher(piece).matches()) {
            out.append("sk9");
        } else if (isAsciiDigits(piece)) {
            out.append('9');
        } else if (isLetters(piece)) {
            out.append('a').append(piece.length());
        } else {
            out.append('m').append(piece.length());
        }
    }

    private static boolean isAsciiDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isLetters(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isLetter(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------ JSON

    private static final int JSON_MAX_DEPTH = 6;
    private static final int JSON_MAX_FIELDS = 60;
    /**
     * The closed set of field names a JSON shape may name: the documented fields of the three replies (Management Center
     * getdevicelicense and getversion, Infoblox license:gridwide and its paging envelope) plus generic envelope words. Any
     * other key is masked as {@code m<len>}: a reply keyed by a device name or serial must not put that key in a log.
     */
    private static final Set<String> JSON_FIELDS = Set.of(
            "uuid", "name", "deviceType", "manufacturer", "serialNumber", "creationDate", "lastModifiedDate",
            "trialPeriodExpirationDate", "licenseStatus", "components", "attributes", "componentName", "productDescription",
            "partNumber", "activationDate", "expirationDate", "validity", "version", "id", "buildNumber",
            "result", "type", "kind", "expiry_date", "expiration_status", "limit", "limit_context", "hwid", "key", "_ref",
            "next_page_id", "value", "code", "message", "error", "Error", "text", "status", "count", "ok");

    /** Field paths with JSON types and array lengths, no value. Example: {@code components[3]{componentName:string}}. */
    public static String jsonShape(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "-";
        }
        Node merged = new Node();
        merged.add(node, 0);
        String shape = merged.render(0);
        if (shape.length() > MAX_TOTAL_CHARS) {
            return shape.substring(0, MAX_TOTAL_CHARS) + " [truncated:chars]";
        }
        return shape;
    }

    private static final class Node {
        private final java.util.TreeSet<String> scalars = new java.util.TreeSet<>();
        private boolean object;
        private boolean array;
        private int minLength = Integer.MAX_VALUE;
        private int maxLength = -1;
        private Node element;
        private final Map<String, Node> fields = new LinkedHashMap<>();
        private boolean fieldsCapped;
        private boolean deeper;

        void add(JsonNode n, int depth) {
            if (n.isObject()) {
                object = true;
                if (depth >= JSON_MAX_DEPTH) {
                    deeper = true;
                    return;
                }
                java.util.Iterator<Map.Entry<String, JsonNode>> it = n.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> f = it.next();
                    String key = JSON_FIELDS.contains(f.getKey()) ? f.getKey() : "m" + f.getKey().length();
                    if (!fields.containsKey(key) && fields.size() >= JSON_MAX_FIELDS) {
                        fieldsCapped = true;
                        continue;
                    }
                    fields.computeIfAbsent(key, k -> new Node()).add(f.getValue(), depth + 1);
                }
            } else if (n.isArray()) {
                array = true;
                minLength = Math.min(minLength, n.size());
                maxLength = Math.max(maxLength, n.size());
                if (depth >= JSON_MAX_DEPTH) {
                    deeper = true;
                    return;
                }
                for (JsonNode child : n) {
                    if (element == null) {
                        element = new Node();
                    }
                    element.add(child, depth + 1);
                }
            } else if (n.isTextual()) {
                scalars.add("string");
            } else if (n.isNumber()) {
                scalars.add("number");
            } else if (n.isBoolean()) {
                scalars.add("boolean");
            } else if (n.isNull()) {
                scalars.add("null");
            } else {
                scalars.add("other");
            }
        }

        String render(int depth) {
            List<String> parts = new ArrayList<>();
            if (object) {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<String, Node> f : fields.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    String child = f.getValue().render(depth + 1);
                    sb.append(f.getKey());
                    if (!(child.startsWith("[") || child.startsWith("{"))) {
                        sb.append(':');
                    }
                    sb.append(child);
                }
                if (fieldsCapped) {
                    sb.append(first ? "" : ",").append("...");
                }
                if (deeper) {
                    sb.append(first && !fieldsCapped ? "" : ",").append("...");
                }
                parts.add(sb.append('}').toString());
            }
            if (array) {
                StringBuilder sb = new StringBuilder("[");
                sb.append(minLength == maxLength ? String.valueOf(maxLength) : minLength + ".." + maxLength).append(']');
                if (element != null) {
                    String child = element.render(depth + 1);
                    sb.append(child);
                } else if (deeper) {
                    sb.append("...");
                }
                parts.add(sb.toString());
            }
            parts.addAll(scalars);
            if (parts.isEmpty()) {
                return "empty";
            }
            return parts.size() == 1 ? parts.get(0) : "(" + String.join("|", parts) + ")";
        }
    }

    // ------------------------------------------------------------------------------------------ the log line

    /**
     * {@code [LIFECYCLE_MEASURE] kind=.. device=.. outcome=.. len=.. lines=.. shape=..}. Never the target host or
     * address, never raw output.
     */
    public static String line(Kind kind, String deviceId, String outcome, int len, int lines, String shape) {
        return "[LIFECYCLE_MEASURE] kind=" + kind.token() + " device=" + safeDevice(deviceId) + " outcome=" + safeOutcome(outcome)
                + " len=" + len + " lines=" + lines + " shape=" + (shape == null || shape.isEmpty() ? "-" : shape);
    }

    /** A CLI read that answered (or answered nothing). */
    public static String cliLine(Kind kind, String deviceId, String output) {
        if (output == null || output.isBlank()) {
            return line(kind, deviceId, "EMPTY", output == null ? 0 : output.length(), 0, "-");
        }
        return line(kind, deviceId, "OK", output.length(), lineCount(output), cliShape(kind, output));
    }

    /** A read that threw or was refused: the outcome names the exception or result class only. */
    public static String errorLine(Kind kind, String deviceId, String errorName) {
        return line(kind, deviceId, "ERROR:" + errorName, 0, 0, "-");
    }

    /** A read that was not sent, and why (a fixed reason token). */
    public static String skippedLine(Kind kind, String deviceId, String reason) {
        return line(kind, deviceId, "SKIPPED:" + reason, 0, 0, "-");
    }

    /** An HTTPS read: a non-2xx status or a body that is not JSON is an ERROR, an empty body EMPTY. */
    public static String jsonLine(Kind kind, String deviceId, int status, String body, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (status < 200 || status > 299) {
            return errorLine(kind, deviceId, "Http" + status);
        }
        if (body == null || body.isBlank()) {
            return line(kind, deviceId, "EMPTY", body == null ? 0 : body.length(), 0, "-");
        }
        try {
            return line(kind, deviceId, "OK", body.length(), lineCount(body), jsonShape(mapper.readTree(body)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return line(kind, deviceId, "ERROR:" + e.getClass().getSimpleName(), body.length(), lineCount(body), "-");
        }
    }

    /** Logs a measurement line (INFO). */
    public static void log(String line) {
        LOG.log(System.Logger.Level.INFO, "{0}", line);
    }

    private static int lineCount(String text) {
        int n = 0;
        for (String l : text.split("\\R", -1)) {
            n++;
        }
        return text.endsWith("\n") ? n - 1 : n;
    }

    private static String safeDevice(String deviceId) {
        return deviceId != null && deviceId.matches("[A-Za-z0-9_.:-]{1,64}") ? deviceId : "unknown";
    }

    private static String safeOutcome(String outcome) {
        return outcome != null && outcome.matches("[A-Za-z0-9_.:-]{1,80}") ? outcome : "ERROR:Invalid";
    }
}
