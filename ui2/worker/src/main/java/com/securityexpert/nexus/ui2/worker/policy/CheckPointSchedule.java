package com.securityexpert.nexus.ui2.worker.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.policy.PolicySchedule;
import java.time.*;
import java.time.format.*;
import java.util.*;

/** Existing dictionary only. Shape: CheckPointSW/terraform-provider-checkpoint data_source_checkpoint_management_time.go. */
final class CheckPointSchedule {
    private CheckPointSchedule() {}
    static PolicySchedule parse(JsonNode node) {
        try {
            String start = node.path("start-now").asBoolean(false) ? null : date(node.path("start"));
            String end = node.path("end-never").asBoolean(false) ? null : date(node.path("end"));
            var recurrence = node.path("recurrence");
            var hours = node.path("hours-ranges");
            if (recurrence.isMissingNode() || recurrence.path("pattern").asText().equalsIgnoreCase("none")) {
                if (hours.isArray()) for (JsonNode range : hours) if (range.path("enabled").asBoolean(false)) return PolicySchedule.unknown();
                if (start == null && end == null && !node.path("end-never").asBoolean(false)) return PolicySchedule.unknown();
                return new PolicySchedule("one-time", start, end, List.of(), "UTC", false);
            }
            String pattern = recurrence.path("pattern").asText().toLowerCase(Locale.ROOT);
            // "Interval" is deliberately unknown until its day/hour semantics are corroborated.
            if (!Set.of("daily", "weekly", "monthly").contains(pattern) || !hours.isArray()) return PolicySchedule.unknown();
            List<Integer> days = new ArrayList<>(), monthDays = new ArrayList<>(), months = new ArrayList<>();
            if (pattern.equals("weekly")) {
                for (JsonNode day : recurrence.path("weekdays")) {
                    String label = day.asText().toLowerCase(Locale.ROOT);
                    var match = Arrays.stream(DayOfWeek.values()).filter(d -> d.name().toLowerCase(Locale.ROOT).startsWith(label) && label.length() >= 3).findFirst();
                    if (match.isEmpty()) return PolicySchedule.unknown();
                    days.add(match.get().getValue());
                }
                if (days.isEmpty()) return PolicySchedule.unknown();
            }
            if (pattern.equals("monthly")) {
                for (JsonNode range : recurrence.path("days")) {
                    String[] parts = range.asText().split("-", -1);
                    int from = Integer.parseInt(parts[0]), to = parts.length == 1 ? from : parts.length == 2 ? Integer.parseInt(parts[1]) : -1;
                    if (from < 1 || to > 31 || from > to) return PolicySchedule.unknown();
                    for (int day = from; day <= to; day++) monthDays.add(day);
                }
                if (monthDays.isEmpty()) return PolicySchedule.unknown();
                String month = recurrence.path("month").asText();
                if (!month.equalsIgnoreCase("any")) {
                    int number = Integer.parseInt(month);
                    if (number < 1 || number > 12) return PolicySchedule.unknown();
                    months.add(number);
                }
            }
            List<PolicySchedule.Window> windows = new ArrayList<>();
            for (JsonNode range : hours) {
                if (!range.path("enabled").isBoolean()) return PolicySchedule.unknown();
                if (!range.path("enabled").asBoolean()) continue;
                windows.add(new PolicySchedule.Window(days, monthDays,
                        PolicySchedule.hour(range.path("from").asText()), PolicySchedule.hour(range.path("to").asText()), months));
            }
            if (windows.isEmpty()) return PolicySchedule.unknown();
            return new PolicySchedule("recurring", start, end, windows, "UTC", false);
        } catch (RuntimeException invalid) { return PolicySchedule.unknown(); }
    }
    private static String date(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        if (!node.isObject()) return PolicySchedule.date(node.asText());
        if (node.has("date") && node.has("time")) return LocalDateTime.parse(node.path("date").asText() + " " + node.path("time").asText(),
                DateTimeFormatter.ofPattern("dd-MMM-uuuu HH:mm", Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)).toString();
        // Vendor documents schedule ISO offsets as ignored: retain wall-clock time, unlike modification timestamps.
        String iso = PolicySchedule.date(node.path("iso-8601").asText());
        try { return OffsetDateTime.parse(iso).toLocalDateTime().toString(); }
        catch (DateTimeParseException local) { return LocalDateTime.parse(iso).toString(); }
    }
}
