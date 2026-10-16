package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Reads one telemetry text file in a single streaming pass. The format is decided from the content of the first
 * {@value #SNIFF_LINES} lines (never from the file name); everything after that is fed to that format's parser line by line.
 * A file whose head matches no known format is reported as not recognised, with a closed reason code, and is not read
 * further. Malformed lines inside a recognised file are skipped one by one and counted.
 */
public final class TelemetryParser {
    public static final int SNIFF_LINES = 400;
    /** Samples kept per file over all its series (about 160 MB); later lines are skipped and counted, the report says so. */
    public static final long MAX_SAMPLES = 8_000_000L;
    /** Program-name placeholders the syslog reader uses (they are not names and are never masked). */
    public static final String OTHER_PROGRAMS_LABEL = "(diğer)";
    public static final String NO_TAG_LABEL = "(etiket yok)";

    /**
     * @param referenceEpochMillis a time near the data (the capture's start), used only to choose the year of syslog lines
     *                             that carry none; {@code null} when unknown
     */
    public record Context(Long referenceEpochMillis) {
        public static Context none() {
            return new Context(null);
        }
    }

    /** The outcome of sniffing the head of a file. */
    public record Detection(TelemetryFormat format, String reason) {
    }

    private TelemetryParser() {
    }

    /** Sniffs only the head of the stream (cheap; used at upload). */
    public static Detection detect(InputStream in) throws IOException {
        BoundedLines lines = new BoundedLines(in);
        List<String> head = new ArrayList<>();
        String line;
        while (head.size() < SNIFF_LINES && (line = lines.next()) != null) {
            head.add(line);
        }
        return detect(head);
    }

    static Detection detect(List<String> head) {
        int nonBlank = 0;
        int syslog = 0;
        boolean banner = false;
        for (String line : head) {
            if (line.isBlank()) {
                continue;
            }
            nonBlank++;
            if (Sysstat.BANNER.matcher(line.strip()).matches()) {
                banner = true;
            } else if (PidstatParser.isHeader(line)) {
                return new Detection(TelemetryFormat.PIDSTAT, null);
            } else if (SarParser.isKnownHeader(line)) {
                return new Detection(TelemetryFormat.SAR, null);
            } else if (VmstatParser.isHeader(line.strip())) {
                return new Detection(TelemetryFormat.VMSTAT, null);
            } else if (SnapshotParser.QDISC.matcher(line).find()
                    || line.length() >= 80 && SnapshotParser.SOFTNET.matcher(line).matches()) {
                return new Detection(TelemetryFormat.SNAPSHOTS, null);
            } else if (SyslogParser.looksLikeSyslog(line)) {
                syslog++;
            }
        }
        if (nonBlank == 0) {
            return new Detection(TelemetryFormat.UNKNOWN, "EMPTY");
        }
        if (syslog >= 3 && syslog * 10 >= nonBlank * 6) {
            return new Detection(TelemetryFormat.SYSLOG, null);
        }
        return new Detection(TelemetryFormat.UNKNOWN, banner ? "NO_KNOWN_BLOCK" : "NO_KNOWN_FORMAT");
    }

    public static ParsedTelemetry parse(InputStream in, Context context) throws IOException {
        return parse(in, context, MAX_SAMPLES);
    }

    static ParsedTelemetry parse(InputStream in, Context context, long maxSamples) throws IOException {
        Context ctx = context == null ? Context.none() : context;
        BoundedLines lines = new BoundedLines(in);
        List<String> head = new ArrayList<>();
        BitSet cut = new BitSet();
        String line;
        while (head.size() < SNIFF_LINES && (line = lines.next()) != null) {
            if (lines.truncated()) {
                cut.set(head.size());
            }
            head.add(line);
        }
        String next = head.size() == SNIFF_LINES ? lines.next() : null;
        boolean nextCut = next != null && lines.truncated();
        boolean more = next != null;
        Detection detection = detect(head);
        ParsedTelemetry out = new ParsedTelemetry();
        if (detection.format() == TelemetryFormat.UNKNOWN) {
            out.reason(detection.reason());
            for (int i = 0; i < head.size(); i++) {
                out.total();
            }
            out.partial(more);
            return out;
        }
        out.format(detection.format());
        LineParser parser = switch (detection.format()) {
            case SAR -> new SarParser(out);
            case PIDSTAT -> new PidstatParser(out);
            case VMSTAT -> new VmstatParser(out);
            case SNAPSHOTS -> new SnapshotParser(out);
            case SYSLOG -> new SyslogParser(out, ctx.referenceEpochMillis());
            default -> throw new IllegalStateException("unreachable");
        };
        for (int i = 0; i < head.size(); i++) {
            feed(parser, out, head.get(i), cut.get(i), maxSamples);
        }
        if (more) {
            feed(parser, out, next, nextCut, maxSamples);
            while ((line = lines.next()) != null) {
                feed(parser, out, line, lines.truncated(), maxSamples);
            }
        }
        parser.finish();
        return out;
    }

    private static void feed(LineParser parser, ParsedTelemetry out, String line, boolean truncated, long maxSamples) {
        out.total();
        if (truncated) {
            out.skipped("LINE_TOO_LONG");
            return;
        }
        if (out.samples() >= maxSamples) {
            out.note("SAMPLE_CAP");
            out.skipped("SAMPLE_CAP");
            return;
        }
        parser.line(line);
    }
}
