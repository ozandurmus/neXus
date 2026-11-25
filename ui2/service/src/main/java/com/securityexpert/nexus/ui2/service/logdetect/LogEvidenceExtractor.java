package com.securityexpert.nexus.ui2.service.logdetect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.EventRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.PendingChunk;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.Pointer;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadService;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadService.ScanResult;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadService.ScanStatus;

/**
 * Turns one stored chunk into evidence rows (design sections 2 and 8.2). The chunk is read only through
 * {@link SystemLogReadService#scanMasked}: decrypted in memory, masked by {@code LogMasking}, and handed over line by
 * line. A line is matched on its masked form and then forgotten; only counts, device-local times, closed-vocabulary key
 * tokens and pointers (chunk id, offset, length) leave this class.
 *
 * <p>Analysed sources in P1 are {@code cp_messages} (the hardware and kernel markers) and {@code pan_system} (the vendor
 * {@code severity} field). The other sources are {@code UNSUPPORTED} until the census measures them (design 3.1, 7.4).</p>
 *
 * <p>Buckets (design 3.4 rule 1): one device-local hour; a {@code kern.boot} anchor is its own bucket per second so each
 * boot stays countable. A line with no readable time counts as unparsed and is never bucketed.</p>
 */
public final class LogEvidenceExtractor {
    public static final String SOURCE_MESSAGES = "cp_messages";
    public static final String SOURCE_PAN_SYSTEM = "pan_system";
    public static final Set<String> ANALYSED_SOURCES = Set.of(SOURCE_MESSAGES, SOURCE_PAN_SYSTEM);
    /** Marker id prefix of a vendor severity value, e.g. {@code sev.pan.critical}. */
    public static final String PAN_MARKER_PREFIX = "sev.pan.";
    private static final int MAX_POINTERS = 3;
    private static final Pattern PAN_SEVERITY = Pattern.compile("\"severity\"\\s*:\\s*\"([A-Za-z]{3,16})\"");
    private static final Pattern PAN_EVENT = Pattern.compile("\"eventid\"\\s*:\\s*\"([A-Za-z0-9_.-]{1,40})\"");

    /** What was extracted: the scan status, the counters and the rows (empty unless the scan was complete). */
    public record Extraction(ScanStatus status, long lines, long matched, long unparsed, List<EventRow> events) {
        public Extraction {
            events = List.copyOf(events);
        }
    }

    private static final class Acc {
        int count;
        LocalDateTime first;
        LocalDateTime last;
        double weight;
        final List<Pointer> pointers = new ArrayList<>(MAX_POINTERS);
    }

    private final HardwareMarkers markers;

    public LogEvidenceExtractor(HardwareMarkers markers) {
        this.markers = markers;
    }

    public Extraction extract(SystemLogReadService reader, PendingChunk chunk) {
        boolean pan = SOURCE_PAN_SYSTEM.equals(chunk.sourceKey());
        Map<String, Acc> accs = new LinkedHashMap<>();
        Map<String, String[]> meta = new LinkedHashMap<>();
        long[] counters = new long[2]; // matched, unparsed
        ScanResult result = reader.scanMasked(chunk.chunkId(), (offset, length, masked) -> {
            LocalDateTime time = pan ? LogLineTimes.pan(masked) : LogLineTimes.gaia(masked);
            if (time == null) {
                counters[1]++;
                return;
            }
            boolean any = false;
            if (pan) {
                any = panLine(masked, time, offset, length, chunk, accs, meta);
            } else {
                for (HardwareMarkers.Hit hit : markers.match(masked)) {
                    any = true;
                    add(accs, meta, hit.marker().id(), hit.keyToken(), hit.marker().anchor() ? time.truncatedTo(ChronoUnit.SECONDS)
                            : time.truncatedTo(ChronoUnit.HOURS), time, hit.marker().weight(), chunk.chunkId(), offset, length);
                }
            }
            if (any) counters[0]++;
        });
        if (result.status() != ScanStatus.OK) {
            return new Extraction(result.status(), result.lines(), 0, 0, List.of());
        }
        List<EventRow> rows = new ArrayList<>();
        for (Map.Entry<String, Acc> e : accs.entrySet()) {
            String[] m = meta.get(e.getKey());
            Acc a = e.getValue();
            LocalDateTime bucket = LocalDateTime.parse(m[2]);
            rows.add(new EventRow(eventId(chunk.chunkId(), m[0], m[1], bucket), chunk.chunkId(), chunk.deviceId(),
                    chunk.sourceKey(), m[0], m[1], bucket, a.weight, a.count, a.first, a.last, a.pointers));
        }
        return new Extraction(ScanStatus.OK, result.lines(), counters[0], counters[1], rows);
    }

    private boolean panLine(String masked, LocalDateTime time, long offset, int length, PendingChunk chunk, Map<String, Acc> accs,
            Map<String, String[]> meta) {
        Matcher sev = PAN_SEVERITY.matcher(masked);
        if (!sev.find()) return false;
        OptionalDouble weight = LogSeverity.panWeight(sev.group(1));
        if (weight.isEmpty() || weight.getAsDouble() < LogSeverity.EVIDENCE_FLOOR) return false;
        Matcher event = PAN_EVENT.matcher(masked);
        String key = event.find() ? event.group(1) : "";
        add(accs, meta, PAN_MARKER_PREFIX + sev.group(1).toLowerCase(java.util.Locale.ROOT), key,
                time.truncatedTo(ChronoUnit.HOURS), time, weight.getAsDouble(), chunk.chunkId(), offset, length);
        return true;
    }

    private static void add(Map<String, Acc> accs, Map<String, String[]> meta, String marker, String key, LocalDateTime bucket,
            LocalDateTime time, double weight, String chunkId, long offset, int length) {
        String id = marker + "\u0000" + key + "\u0000" + bucket;
        Acc a = accs.computeIfAbsent(id, k -> new Acc());
        meta.putIfAbsent(id, new String[] {marker, key, bucket.toString()});
        a.count++;
        a.weight = Math.max(a.weight, weight);
        if (a.first == null || time.isBefore(a.first)) a.first = time;
        if (a.last == null || time.isAfter(a.last)) a.last = time;
        if (a.pointers.size() < MAX_POINTERS) a.pointers.add(new Pointer(chunkId, offset, length));
    }

    /** Deterministic id: the same chunk, marker, key and bucket always give the same row id (idempotent re-index). */
    static String eventId(String chunkId, String marker, String key, LocalDateTime bucket) {
        return sha256Hex(chunkId + "|" + marker + "|" + key + "|" + bucket).substring(0, 32);
    }

    static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
