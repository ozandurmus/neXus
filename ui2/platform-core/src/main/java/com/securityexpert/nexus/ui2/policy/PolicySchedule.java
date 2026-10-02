package com.securityexpert.nexus.ui2.policy;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.w3c.dom.Element;

/** Parsed intent only. Unsupported or incomplete shapes never imply an unrestricted schedule. */
public record PolicySchedule(String kind, String start, String end, List<Window> windows,
        String timezone, boolean timezoneKnown) {
    public record Window(List<Integer> days, List<Integer> monthDays, String start, String end, List<Integer> months) {
        public Window(List<Integer> days, List<Integer> monthDays, String start, String end) { this(days, monthDays, start, end, List.of()); }
        public Window { days = List.copyOf(days); monthDays = List.copyOf(monthDays); months = months == null ? List.of() : List.copyOf(months); }
    }
    public PolicySchedule { windows = List.copyOf(windows); }
    public static PolicySchedule unknown() { return new PolicySchedule("unknown", null, null, List.of(), "UTC", false); }
    public static String date(String value) {
        if (value == null || value.isBlank()) return null;
        try { return OffsetDateTime.parse(value).toString(); } catch (RuntimeException ignored) {}
        for (String format : List.of("uuuu-MM-dd'T'HH:mm", "uuuu-MM-dd'T'HH:mm:ss", "uuuu/MM/dd@HH:mm", "dd-MMM-uuuu HH:mm:ss")) {
            try { return LocalDateTime.parse(value, DateTimeFormatter.ofPattern(format, Locale.ENGLISH).withResolverStyle(java.time.format.ResolverStyle.STRICT)).toString(); }
            catch (RuntimeException ignored) {}
        }
        throw new IllegalArgumentException("Unsupported schedule date");
    }
    public static String hour(String value) { return LocalTime.parse(value).toString(); }
    private ZonedDateTime at(String value) {
        try { return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of(timezone)); }
        catch (RuntimeException ignored) { return LocalDateTime.parse(value).atZone(ZoneId.of(timezone)); }
    }
    public String status(Instant instant) {
        try {
            if (!Set.of("one-time", "recurring").contains(kind)) return "unknown";
            var now = instant.atZone(ZoneId.of(timezone));
            if (start != null && end != null && at(start).isAfter(at(end))) return "unknown";
            if (start != null && now.isBefore(at(start))) return "upcoming";
            if (end != null && now.isAfter(at(end))) return "expired";
            if (kind.equals("one-time")) {
                if (!windows.isEmpty()) return windows.stream().anyMatch(w -> !now.isBefore(at(w.start())) && !now.isAfter(at(w.end()))) ? "active" : "upcoming";
                return start == null && end == null ? "always" : "active";
            }
            if (windows.isEmpty()) return "unknown";
            for (Window w : windows) {
                var from = LocalTime.parse(w.start()); var to = LocalTime.parse(w.end());
                if (from.isAfter(to)) return "unknown";
                if ((w.days().isEmpty() || w.days().contains(now.getDayOfWeek().getValue()))
                        && (w.months().isEmpty() || w.months().contains(now.getMonthValue()))
                        && (w.monthDays().isEmpty() || w.monthDays().contains(now.getDayOfMonth()))
                        && !now.toLocalTime().isBefore(from) && !now.toLocalTime().isAfter(to)) return "active";
            }
            return "upcoming";
        } catch (RuntimeException invalid) { return "unknown"; }
    }
    public boolean expiring(Instant now) {
        try { return end != null && !at(end).toInstant().isBefore(now) && !at(end).toInstant().isAfter(now.plus(Duration.ofDays(14))); }
        catch (RuntimeException invalid) { return false; }
    }
    public static PolicySchedule panorama(Element entry) {
        try {
            List<Window> windows = new ArrayList<>();
            var nonRecurring = PolicyXml.selectRelative(entry, "schedule-type/non-recurring/member");
            if (!nonRecurring.isEmpty()) {
                if (!PolicyXml.selectRelative(entry, "schedule-type/recurring").isEmpty()) return unknown();
                // Multiple one-time ranges retain every interval; no min/max approximation across gaps.
                for (Element range : nonRecurring) {
                    String[] parts = range.getTextContent().trim().split("-", 2);
                    if (parts.length != 2) return unknown();
                    String from = date(parts[0]), to = date(parts[1]);
                    if (from.compareTo(to) > 0) return unknown();
                    windows.add(new Window(List.of(), List.of(), from, to));
                }
                return new PolicySchedule("one-time", windows.stream().map(Window::start).min(String::compareTo).orElse(null),
                        windows.stream().map(Window::end).max(String::compareTo).orElse(null), windows, "UTC", false);
            }
            for (Element range : PolicyXml.selectRelative(entry, "schedule-type/recurring/daily/member"))
                windows.add(range(range.getTextContent(), List.of()));
            for (DayOfWeek day : DayOfWeek.values()) for (Element range : PolicyXml.selectRelative(entry,
                    "schedule-type/recurring/weekly/" + day.name().toLowerCase(Locale.ROOT) + "/member"))
                windows.add(range(range.getTextContent(), List.of(day.getValue())));
            return windows.isEmpty() ? unknown() : new PolicySchedule("recurring", null, null, windows, "UTC", false);
        } catch (RuntimeException invalid) { return unknown(); }
    }
    private static Window range(String value, List<Integer> days) {
        String[] parts = value.trim().split("-", 2);
        if (parts.length != 2) throw new IllegalArgumentException("Unsupported time window");
        return new Window(days, List.of(), hour(parts[0]), hour(parts[1]));
    }
}
