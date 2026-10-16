package com.securityexpert.nexus.ui2.packetanalysis.export;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Inputs of the expert report that do not come from the case database.
 *
 * @param windowStart explicit start of the event window in epoch seconds (null: the engine's first onset)
 * @param windowEnd   explicit end of the event window in epoch seconds (null: the end of the common window);
 *                    only valid together with {@code windowStart}
 * @param topCycles   number of longest transactions listed in the sample-events sheet (1..{@value #MAX_TOP_CYCLES})
 * @param masker      the privacy projection ({@link ReportMasker#NONE} for raw-view roles)
 * @param sources     the uploaded captures with their SHA-256 values (the case database does not hold them)
 * @param telemetry   host telemetry files attached to the case with the alignment the user confirmed (may be empty)
 * @param caseTitle   the case's title for the workbook heading, <b>already projected</b> by the caller (the masked
 *                    pseudonym for a masked reader); {@code null} or blank: a generic heading
 */
public record ExpertReportOptions(Double windowStart, Double windowEnd, int topCycles, ReportMasker masker,
        List<SourceCapture> sources, List<HostTelemetry> telemetry, String caseTitle) {
    public static final int DEFAULT_TOP_CYCLES = 25;
    public static final int MAX_TOP_CYCLES = 100;

    /** One uploaded capture: the display name the case database uses, its size and SHA-256. */
    public record SourceCapture(String name, long sizeBytes, String sha256) {
    }

    /** Opens a stored file afresh each time it is read. */
    @FunctionalInterface
    public interface Opener {
        InputStream open() throws IOException;
    }

    /**
     * One attached host telemetry file and the alignment the user gave it.
     *
     * @param id            opaque id (orders files of equal name)
     * @param name          display name of the upload
     * @param sha256        content digest (shown only to raw-view readers)
     * @param timeZone      IANA id or offset the file's clock is written in; {@code null}: not confirmed, UTC assumed
     * @param offsetSeconds extra clock offset added to the file's time
     * @param anchorLocal   {@code yyyy-MM-dd HH:mm:ss} of the first line for files that carry no date or no time, or {@code null}
     */
    public record HostTelemetry(String id, String name, long sizeBytes, String sha256, String timeZone, int offsetSeconds,
            String anchorLocal, Opener opener) {
        public HostTelemetry {
            if (id == null || name == null || opener == null) {
                throw new IllegalArgumentException("A telemetry file needs an id, a name and a way to read it");
            }
        }
    }

    /** Without a case title. */
    public ExpertReportOptions(Double windowStart, Double windowEnd, int topCycles, ReportMasker masker,
            List<SourceCapture> sources, List<HostTelemetry> telemetry) {
        this(windowStart, windowEnd, topCycles, masker, sources, telemetry, null);
    }

    /** Without host telemetry. */
    public ExpertReportOptions(Double windowStart, Double windowEnd, int topCycles, ReportMasker masker,
            List<SourceCapture> sources) {
        this(windowStart, windowEnd, topCycles, masker, sources, List.of(), null);
    }

    public ExpertReportOptions {
        if (windowStart != null && !Double.isFinite(windowStart) || windowEnd != null && !Double.isFinite(windowEnd)) {
            throw new IllegalArgumentException("Window start and end must be finite epoch seconds");
        }
        if (windowStart == null && windowEnd != null) {
            throw new IllegalArgumentException("A window end needs a window start");
        }
        if (windowStart != null && windowEnd != null && windowEnd <= windowStart) {
            throw new IllegalArgumentException("The window end must be after the window start");
        }
        if (topCycles < 1 || topCycles > MAX_TOP_CYCLES) {
            throw new IllegalArgumentException("Top cycles must be between 1 and " + MAX_TOP_CYCLES);
        }
        masker = masker == null ? ReportMasker.NONE : masker;
        sources = sources == null ? List.of() : List.copyOf(sources);
        telemetry = telemetry == null ? List.of() : List.copyOf(telemetry);
        caseTitle = caseTitle == null || caseTitle.isBlank() ? null : caseTitle.strip();
    }

    public static ExpertReportOptions defaults() {
        return new ExpertReportOptions(null, null, DEFAULT_TOP_CYCLES, ReportMasker.NONE, List.of(), List.of(), null);
    }

    public ExpertReportOptions withWindow(Double start, Double end) {
        return new ExpertReportOptions(start, end, topCycles, masker, sources, telemetry, caseTitle);
    }

    public ExpertReportOptions withMasker(ReportMasker value) {
        return new ExpertReportOptions(windowStart, windowEnd, topCycles, value, sources, telemetry, caseTitle);
    }

    public ExpertReportOptions withSources(List<SourceCapture> value) {
        return new ExpertReportOptions(windowStart, windowEnd, topCycles, masker, value, telemetry, caseTitle);
    }

    public ExpertReportOptions withTelemetry(List<HostTelemetry> value) {
        return new ExpertReportOptions(windowStart, windowEnd, topCycles, masker, sources, value, caseTitle);
    }

    /** The heading's case title, already projected for the reader (masked for a masked projection). */
    public ExpertReportOptions withCaseTitle(String value) {
        return new ExpertReportOptions(windowStart, windowEnd, topCycles, masker, sources, telemetry, value);
    }

    public ExpertReportOptions withTopCycles(int value) {
        return new ExpertReportOptions(windowStart, windowEnd, value, masker, sources, telemetry, caseTitle);
    }
}
