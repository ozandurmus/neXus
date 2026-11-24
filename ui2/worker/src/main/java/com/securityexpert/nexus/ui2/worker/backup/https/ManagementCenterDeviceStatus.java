package com.securityexpert.nexus.ui2.worker.backup.https;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.persistence.device.inventory.GridMember;

/**
 * A Symantec Management Center's {@code GET /api/devices/status} answer (docs/design/MC_DEVICE_HEALTH_READ_PROPOSAL_2026_11_24.md,
 * candidate C1; documented {@code StatusData}: uuid, name, checkDate, startDate, warnings[], errors[], monitorState) reduced in
 * memory to what the Devices screen shows per managed device, joined to the device list by the exact, opaque {@code uuid}:
 *
 * <ul>
 * <li>the health state from {@code monitorState} only -- {@code ERROR}, {@code WARNING}, {@code OK} or {@code INACTIVE} when the
 * vendor token is exactly that, otherwise {@code UNKNOWN} (the documented example value {@code MONITORED} is not a health colour);
 * an {@code OK} that comes with listed errors or warnings is raised to {@code ERROR} / {@code WARNING}, never the other way;</li>
 * <li>the error and warning counts, the check time exactly as the MC sent it (only when it has an ISO date-time shape), and at most
 * {@value #MAX_MESSAGES} short vendor texts with addresses, hostnames and identifiers masked.</li>
 * </ul>
 *
 * The raw JSON, {@code properties}, {@code startDate} and the managed devices' names are discarded. A device the answer does not
 * list (or lists twice) is {@code UNKNOWN}, never {@code OK}. Every health is manager-reported, never a direct device read.
 */
final class ManagementCenterDeviceStatus {

    /** The closed reasons a health is UNKNOWN, stored as the single message (V271). */
    static final String GATE_NOT_APPROVED = "GATE_NOT_APPROVED";
    static final String HEALTH_READ_FAILED = "HEALTH_READ_FAILED";
    static final String NOT_IN_STATUS_ANSWER = "NOT_IN_STATUS_ANSWER";
    static final String AMBIGUOUS_STATUS = "AMBIGUOUS_STATUS";

    static final int MAX_MESSAGES = 3;
    static final int MAX_MESSAGE_CHARS = 160;

    private static final Pattern CHECK_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:?\\d{2})?");
    private static final Pattern MESSAGE_ID = Pattern.compile("/[A-Za-z0-9_./-]{1,80}");
    private static final Pattern UUID_TEXT = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    // Over-masks on purpose: any colon-separated run of hex groups (an IPv6 literal, but also a clock time) is replaced.
    private static final Pattern IPV6 = Pattern.compile("(?i)(?<![\\w:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![\\w:])");
    private static final Pattern HOSTNAME = Pattern.compile("\\b[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+\\b");
    private static final Pattern LONG_HEX_OR_DIGITS = Pattern.compile("\\b[0-9A-Fa-f]{16,}\\b");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\s]+");
    private static final int MIN_KNOWN_NAME = 3;

    private ManagementCenterDeviceStatus() {
    }

    /** What the status read produced: a health per listed uuid, or none (the read was not made or failed). */
    record Answer(Map<String, GridMember.Health> byUuid) {
        Answer {
            byUuid = Map.copyOf(byUuid);
        }
    }

    /**
     * The per-uuid health of a {@code GET /api/devices/status} answer, or empty when the answer is not the documented array.
     *
     * @param status      the parsed answer
     * @param knownTexts  names, hosts and serials the same run's device list carries; replaced in every message
     */
    static Optional<Answer> parse(JsonNode status, Collection<String> knownTexts) {
        if (status == null || !status.isArray()) {
            return Optional.empty();
        }
        List<String> known = new ArrayList<>(knownTexts == null ? List.of() : knownTexts);
        for (JsonNode entry : status) {
            text(entry, "name").ifPresent(known::add);
        }
        List<Pattern> knownPatterns = knownPatterns(known);
        Map<String, GridMember.Health> out = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode entry : status) {
            if (entry == null || !entry.isObject()) {
                continue;
            }
            Optional<String> uuid = text(entry, "uuid").filter(HttpsVendorPlan::isMcDeviceUuid);
            if (uuid.isEmpty()) {
                continue;
            }
            if (!seen.add(uuid.get())) {
                out.put(uuid.get(), GridMember.Health.unknown(AMBIGUOUS_STATUS));
                continue;
            }
            out.put(uuid.get(), health(entry, knownPatterns));
        }
        return Optional.of(new Answer(out));
    }

    /** The health of {@code uuid}: the answer's, or UNKNOWN {@value #NOT_IN_STATUS_ANSWER} when the answer does not list it. */
    static GridMember.Health healthFor(Answer answer, String uuid) {
        GridMember.Health health = uuid == null ? null : answer.byUuid().get(uuid);
        return health != null ? health : GridMember.Health.unknown(NOT_IN_STATUS_ANSWER);
    }

    private static GridMember.Health health(JsonNode entry, List<Pattern> known) {
        List<String> errors = issues(entry.path("errors"), "Error", known);
        List<String> warnings = issues(entry.path("warnings"), "Warning", known);
        int errorCount = count(entry.path("errors"));
        int warningCount = count(entry.path("warnings"));
        String state = state(text(entry, "monitorState").orElse(null));
        if ("OK".equals(state)) {
            state = errorCount > 0 ? "ERROR" : warningCount > 0 ? "WARNING" : "OK";
        }
        List<String> messages = new ArrayList<>(errors);
        messages.addAll(warnings);
        if (messages.size() > MAX_MESSAGES) {
            messages = new ArrayList<>(messages.subList(0, MAX_MESSAGES));
        }
        Optional<String> checked = text(entry, "checkDate").filter(d -> CHECK_DATE.matcher(d).matches());
        return new GridMember.Health(state, errorCount, warningCount, checked, messages);
    }

    /** The vendor token as a closed state; any other token (or none) is UNKNOWN. */
    static String state(String monitorState) {
        if (monitorState == null) {
            return "UNKNOWN";
        }
        return switch (monitorState.strip().toUpperCase(Locale.ROOT)) {
            case "ERROR" -> "ERROR";
            case "WARNING" -> "WARNING";
            case "OK" -> "OK";
            case "INACTIVE" -> "INACTIVE";
            default -> "UNKNOWN";
        };
    }

    private static int count(JsonNode list) {
        return list != null && list.isArray() ? list.size() : 0;
    }

    private static List<String> issues(JsonNode list, String label, List<Pattern> known) {
        List<String> out = new ArrayList<>();
        if (list == null || !list.isArray()) {
            return out;
        }
        for (JsonNode issue : list) {
            if (out.size() >= MAX_MESSAGES) {
                break;
            }
            String message = text(issue, "message").or(() -> text(issue, "messageId").filter(id -> MESSAGE_ID.matcher(id).matches())).orElse(null);
            if (message == null) {
                continue;
            }
            out.add(label + ": " + mask(message, known));
        }
        return out;
    }

    /**
     * One vendor text with the addresses, hostnames, identifiers and the run's own device names replaced by placeholders,
     * whitespace collapsed, at most {@value #MAX_MESSAGE_CHARS} characters. Over-masking is deliberate.
     */
    static String mask(String raw, List<Pattern> known) {
        String s = CONTROL.matcher(raw == null ? "" : raw).replaceAll(" ").strip();
        if (s.length() > 4 * MAX_MESSAGE_CHARS) {
            s = s.substring(0, 4 * MAX_MESSAGE_CHARS);
        }
        for (Pattern p : known) {
            s = p.matcher(s).replaceAll("[device]");
        }
        s = UUID_TEXT.matcher(s).replaceAll("[id]");
        s = EMAIL.matcher(s).replaceAll("[email]");
        s = IPV4.matcher(s).replaceAll("[ip]");
        s = IPV6.matcher(s).replaceAll("[ip]");
        s = HOSTNAME.matcher(s).replaceAll("[host]");
        s = LONG_HEX_OR_DIGITS.matcher(s).replaceAll("[id]");
        if (s.length() > MAX_MESSAGE_CHARS) {
            s = s.substring(0, MAX_MESSAGE_CHARS - 3).stripTrailing() + "...";
        }
        return s;
    }

    /** Case-insensitive literal patterns of the known texts, longest first so a long name is not half-replaced by a short one. */
    static List<Pattern> knownPatterns(Collection<String> texts) {
        return texts.stream().filter(t -> t != null && t.strip().length() >= MIN_KNOWN_NAME).map(String::strip).distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(t -> Pattern.compile(Pattern.quote(t), Pattern.CASE_INSENSITIVE)).toList();
    }

    private static Optional<String> text(JsonNode n, String field) {
        return n != null && n.hasNonNull(field) && n.get(field).isValueNode() && !n.get(field).asText().isBlank()
                ? Optional.of(n.get(field).asText().trim()) : Optional.empty();
    }
}
