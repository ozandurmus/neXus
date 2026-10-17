package com.securityexpert.nexus.ui2.worker.systemlog;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First and last line time of a chunk (contract section 4.7). The time of a chunk is the time of its first and its
 * last <em>timestamped</em> line, never of a continuation line. A line is timestamped only when it matches the
 * measured form of its source; everything else, an unrecognised month token included, is {@link LineTime#UNKNOWN}.
 *
 * <p>The measured forms (shapes inferred from the masked measurement output):</p>
 * <ul>
 * <li>{@code cp_messages}: {@code <Mon> <DD> <HH:MM:SS> <YYYY> <host> ...}; the year is printed.</li>
 * <li>{@code cp_fwd_elg}, {@code cp_cpd_elg}, {@code cp_cpwd_elg}:
 *     {@code [<proc> <pid> <tid>]@<host>[<DD> <Mon> <HH:MM:SS>] <text>}; no year.</li>
 * <li>{@code cp_fwk_elg}: {@code [<DD> <Mon> <HH:MM:SS>][<instance>];[<cpu>];...}; no year.</li>
 * <li>{@code cp_mq_mng_elg}, {@code cp_spike_log}, {@code cp_spike_csv}: not measured, always unknown.</li>
 * </ul>
 * A year-less date takes the latest year in which that day and month is not after the device clock of the last stat
 * (plus the configured slack, because the device's time zone is not known). Only the first
 * {@value #MAX_HEADER_CHARS} characters of a line are inspected.
 */
public final class LineTimes {
    static final int MAX_HEADER_CHARS = 256;
    private static final int YEAR_LOOKBACK = 8;

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4),
            Map.entry("may", 5), Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8),
            Map.entry("sep", 9), Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));

    private static final Pattern MESSAGES = Pattern.compile(
            "^([A-Za-z]{3})\\s+(\\d{1,2})\\s+(\\d{2}):(\\d{2}):(\\d{2})\\s+(\\d{4})(?:\\s.*)?$", Pattern.DOTALL);
    private static final Pattern ELG = Pattern.compile(
            "^\\[[^\\]]*\\]@[^\\s\\[\\]]*\\[(\\d{1,2}) ([A-Za-z]{3}) (\\d{2}):(\\d{2}):(\\d{2})\\].*$", Pattern.DOTALL);
    private static final Pattern FWK = Pattern.compile(
            "^\\[(\\d{1,2}) ([A-Za-z]{3}) (\\d{2}):(\\d{2}):(\\d{2})\\]\\[[^\\]]*\\];\\[[^\\]]*\\];.*$", Pattern.DOTALL);
    private static final Pattern PAN_RECEIVE_TIME = Pattern.compile(
            "^[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}$");

    private LineTimes() {
    }

    /** The first and last timestamped line time of one chunk; both unknown when no line is timestamped. */
    public record ChunkTimes(LineTime first, LineTime last) {
        public static final ChunkTimes UNKNOWN = new ChunkTimes(LineTime.UNKNOWN, LineTime.UNKNOWN);
    }

    /**
     * @param chunk the stored bytes of the chunk (any encoding: only the ASCII header of a line is read)
     * @param deviceEpochAtRead the device clock of the stat of this run, for the year of year-less lines
     */
    public static ChunkTimes extract(SourceFamily family, byte[] chunk, long deviceEpochAtRead, PlannerConfig config) {
        if (!hasMeasuredForm(family) || chunk == null || chunk.length == 0) {
            return ChunkTimes.UNKNOWN;
        }
        LineTime first = LineTime.UNKNOWN;
        int position = 0;
        while (position < chunk.length) {
            int end = lineEnd(chunk, position);
            LineTime t = parseLine(family, chunk, position, end, deviceEpochAtRead, config);
            if (t.known()) {
                first = t;
                break;
            }
            position = end + 1;
        }
        if (!first.known()) {
            return ChunkTimes.UNKNOWN;
        }
        LineTime last = first;
        int end = chunk.length;
        if (end > 0 && chunk[end - 1] == '\n') {
            end--;
        }
        while (end >= 0) {
            int start = lineStart(chunk, end);
            LineTime t = parseLine(family, chunk, start, end, deviceEpochAtRead, config);
            if (t.known()) {
                last = t;
                break;
            }
            if (start == 0) {
                break;
            }
            end = start - 1;
        }
        return new ChunkTimes(first, last);
    }

    /** One line, for callers that classify single lines; unknown when the line is not in the measured form. */
    public static LineTime parseLine(SourceFamily family, String line, long deviceEpochAtRead, PlannerConfig config) {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        return parseLine(family, bytes, 0, bytes.length, deviceEpochAtRead, config);
    }

    /** PAN {@code receive_time}: kept verbatim as the cursor; only the {@code yyyy/MM/dd HH:mm:ss} shape is valid. */
    public static String panReceiveTime(String receiveTime) {
        return receiveTime != null && PAN_RECEIVE_TIME.matcher(receiveTime).matches() ? receiveTime : null;
    }

    static boolean hasMeasuredForm(SourceFamily family) {
        return switch (family) {
            case CP_MESSAGES, CP_FWD_ELG, CP_CPD_ELG, CP_CPWD_ELG, CP_FWK_ELG -> true;
            case CP_MQ_MNG_ELG, CP_SPIKE_LOG, CP_SPIKE_CSV -> false;
        };
    }

    private static LineTime parseLine(SourceFamily family, byte[] data, int start, int end, long deviceEpoch,
            PlannerConfig config) {
        if (!hasMeasuredForm(family) || end <= start) {
            return LineTime.UNKNOWN;
        }
        int length = Math.min(end - start, MAX_HEADER_CHARS);
        if (length > 0 && data[start + length - 1] == '\r' && start + length == end) {
            length--;
        }
        String head = new String(data, start, length, StandardCharsets.ISO_8859_1);
        try {
            switch (family) {
                case CP_MESSAGES: {
                    Matcher m = MESSAGES.matcher(head);
                    if (!m.matches()) {
                        return LineTime.UNKNOWN;
                    }
                    Integer month = MONTHS.get(m.group(1).toLowerCase(Locale.ROOT));
                    if (month == null) {
                        return LineTime.UNKNOWN;
                    }
                    return LineTime.of(LocalDateTime.of(Integer.parseInt(m.group(6)), month,
                            Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                            Integer.parseInt(m.group(5))));
                }
                case CP_FWD_ELG:
                case CP_CPD_ELG:
                case CP_CPWD_ELG:
                    return yearless(ELG.matcher(head), deviceEpoch, config);
                case CP_FWK_ELG:
                    return yearless(FWK.matcher(head), deviceEpoch, config);
                default:
                    return LineTime.UNKNOWN;
            }
        } catch (DateTimeException | NumberFormatException e) {
            return LineTime.UNKNOWN; // 31 Feb, 25:61:61, a year out of range: not a time
        }
    }

    /** Groups: 1 day, 2 month token, 3 hour, 4 minute, 5 second. */
    private static LineTime yearless(Matcher m, long deviceEpoch, PlannerConfig config) {
        if (!m.matches()) {
            return LineTime.UNKNOWN;
        }
        Integer month = MONTHS.get(m.group(2).toLowerCase(Locale.ROOT));
        if (month == null) {
            return LineTime.UNKNOWN;
        }
        int day = Integer.parseInt(m.group(1));
        LocalDate limit = Instant.ofEpochSecond(deviceEpoch).atZone(ZoneOffset.UTC).toLocalDate()
                .plusDays(config.yearInferenceSlackDays());
        for (int year = limit.getYear(); year > limit.getYear() - YEAR_LOOKBACK; year--) {
            LocalDate date;
            try {
                date = LocalDate.of(year, month, day);
            } catch (DateTimeException e) {
                continue; // 29 Feb in a common year: try the previous year
            }
            if (!date.isAfter(limit)) {
                return LineTime.of(date.atTime(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                        Integer.parseInt(m.group(5))));
            }
        }
        return LineTime.UNKNOWN;
    }

    /** Index of the newline ending the line that starts at {@code from}, or the array length. */
    private static int lineEnd(byte[] data, int from) {
        for (int i = from; i < data.length; i++) {
            if (data[i] == '\n') {
                return i;
            }
        }
        return data.length;
    }

    /** Start index of the line whose end (exclusive) is {@code end}. */
    private static int lineStart(byte[] data, int end) {
        for (int i = end - 1; i >= 0; i--) {
            if (data[i] == '\n') {
                return i + 1;
            }
        }
        return 0;
    }
}
