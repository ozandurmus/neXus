package com.securityexpert.nexus.ui2.service.changes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Check Point {@code cpwd.elg} lines that speak of a process ending or being restarted, as device events. Small and
 * local on purpose (the fleet-health lane owns the estate-wide recognisers; this one is folded into them later): it
 * takes the measured elg header {@code [<proc> <pid> <tid>]@<host>[<DD> <Mon> <HH:MM:SS>] <text>} (findings 2026-10-17
 * section 3.3) and keeps a line whose text contains one of a short vocabulary ({@link #VOCABULARY}). The event's title
 * is the line's own text after the header: the feed repeats what cpwd wrote and does not name a cause.
 *
 * <p>No year is printed in an elg header. The year is the latest one for which the day and month are not after the
 * chunk's collection date (Phase B section 4.3 rule); the time stays {@code DEVICE_LOCAL} and is ordered as
 * {@link PanConfigEvents#ORDERING_ZONE} like every other device clock in the feed.</p>
 */
public final class CpwdEvents {
    /** The words that make a cpwd line a restart/termination line (case-insensitive, substring). */
    static final List<String> VOCABULARY = List.of("terminat", "restart", "kill", "exit code", "not responding", "stopp", "crash");
    static final Pattern HEADER = Pattern.compile(
            "^\\[(?<proc>[^\\s\\]]+) (?<pid>\\d+) (?<tid>\\d+)\\]@(?<host>\\S+)\\[(?<day>\\d{1,2}) (?<mon>[A-Za-z]{3}) (?<time>\\d{2}:\\d{2}:\\d{2})\\]\\s*(?<text>.*)$");
    private static final Map<String, Month> MONTHS = Map.ofEntries(
            Map.entry("jan", Month.JANUARY), Map.entry("feb", Month.FEBRUARY), Map.entry("mar", Month.MARCH),
            Map.entry("apr", Month.APRIL), Map.entry("may", Month.MAY), Map.entry("jun", Month.JUNE),
            Map.entry("jul", Month.JULY), Map.entry("aug", Month.AUGUST), Map.entry("sep", Month.SEPTEMBER),
            Map.entry("oct", Month.OCTOBER), Map.entry("nov", Month.NOVEMBER), Map.entry("dec", Month.DECEMBER));

    private CpwdEvents() {}

    public record Parsed(List<ChangeEvent> events, int lines) {
    }

    /**
     * @param collectedAt when the chunk was collected (fixes the year)
     */
    public static Parsed parse(List<String> lines, String deviceId, String chunkId, Instant collectedAt, Projection projection) {
        List<ChangeEvent> out = new ArrayList<>();
        LocalDate collectedDay = collectedAt.atZone(PanConfigEvents.ORDERING_ZONE).toLocalDate();
        int index = 0;
        for (String line : lines) {
            index++;
            parseLine(line, deviceId, chunkId + ":" + index, collectedDay, projection).ifPresent(out::add);
        }
        return new Parsed(out, lines.size());
    }

    static Optional<ChangeEvent> parseLine(String line, String deviceId, String key, LocalDate collectedDay, Projection projection) {
        if (line == null) return Optional.empty();
        Matcher m = HEADER.matcher(line.strip());
        if (!m.matches()) return Optional.empty();
        String text = m.group("text").strip();
        if (!mentionsRestart(text)) return Optional.empty();
        Month month = MONTHS.get(m.group("mon").toLowerCase(Locale.ROOT));
        if (month == null) return Optional.empty();
        LocalDateTime local;
        try {
            LocalTime time = LocalTime.parse(m.group("time"));
            int day = Integer.parseInt(m.group("day"));
            LocalDate date = LocalDate.of(collectedDay.getYear(), month, day);
            if (date.isAfter(collectedDay)) date = date.minusYears(1);
            local = LocalDateTime.of(date, time);
        } catch (java.time.DateTimeException e) {
            return Optional.empty();
        }
        String deviceTime = m.group("day") + " " + m.group("mon") + " " + m.group("time");
        return Optional.of(new ChangeEvent("cp_cpwd_elg:" + key, ChangeEvent.Kind.DEVICE_EVENT, "cp_cpwd_elg",
                local.atZone(PanConfigEvents.ORDERING_ZONE).toInstant(), "DEVICE_LOCAL", deviceTime, "check_point", deviceId,
                projection.device(deviceId), "cpwd", "cpwd: " + projection.text(text), null, ChangeEvent.Tone.WARN, List.of(),
                new ChangeEvent.Links(PanConfigEvents.deviceLink(deviceId), null, null, null, null)));
    }

    static boolean mentionsRestart(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : VOCABULARY) {
            if (lower.contains(word)) return true;
        }
        return false;
    }
}
