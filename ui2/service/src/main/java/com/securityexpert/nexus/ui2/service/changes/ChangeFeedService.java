package com.securityexpert.nexus.ui2.service.changes;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.changes.ChangeFeedRepository;
import com.securityexpert.nexus.ui2.persistence.changes.ChangeFeedRepository.ChunkRow;
import com.securityexpert.nexus.ui2.persistence.changes.ChangeFeedRepository.CrashAlertRow;
import com.securityexpert.nexus.ui2.persistence.changes.ChangeFeedRepository.EvidenceRow;
import com.securityexpert.nexus.ui2.persistence.changes.ChangeFeedRepository.JobRow;
import com.securityexpert.nexus.ui2.persistence.changes.ChangeFeedRepository.PolicyInstallRow;
import com.securityexpert.nexus.ui2.service.changes.ChangeEvent.Kind;
import com.securityexpert.nexus.ui2.service.changes.ChangeEvent.Links;
import com.securityexpert.nexus.ui2.service.changes.ChangeEvent.Tone;
import com.securityexpert.nexus.ui2.service.policy.PolicyChangesService;

/**
 * Operations > Change feed: "who changed what, when" across the estate as one timeline, newest first, from what
 * neXus already stores and nothing else (no device command). The feed orders and shows; it never interprets
 * causality ("Yorum yapmaz; sıralar ve gösterir", mockup 2026-10-17 screen 3).
 *
 * <p><b>Sources</b> (each a bounded read, each reported in {@code coverage} with its state, so an absent source is
 * named, never shown as "no changes"):</p>
 * <ol>
 *   <li>{@code policy_rule_history} (USER): stored rule revisions with field diffs, Check Point and Palo Alto, through
 *       {@link PolicyChangesService#recent}.</li>
 *   <li>{@code pan_config} (USER): the stored Palo Alto config log, parsed from the encrypted JSON Lines chunks
 *       ({@link PanConfigEvents}).</li>
 *   <li>{@code device_policy_install} (USER): the last policy install each gateway reported (V60; no actor is stored).</li>
 *   <li>{@code policy_change_evidence} (USER): the V163 Panorama config-log and Check Point show-changes evidence,
 *       whose readers are off by default; an empty table is said so.</li>
 *   <li>{@code jobs} (NEXUS_JOB): neXus's own runs, in a neutral colour; a window's identical jobs are folded into one
 *       line with their outcome counts.</li>
 *   <li>{@code cp_cpwd_elg} (DEVICE_EVENT): cpwd restart/termination lines ({@link CpwdEvents}).</li>
 *   <li>{@code device_crash_alert} (DEVICE_EVENT): new crash/dump file alerts (V160).</li>
 * </ol>
 *
 * <p><b>Paging.</b> Each source answers {@code [from, upper)} newest first within its own bound; the merge sorts by
 * (time, key) descending. The cursor is the boundary instant plus the keys already shown at exactly that instant;
 * the next page queries {@code upper = boundary + 1 s} and drops those keys, so an event is shown once and none is
 * skipped. When a source hit its bound, the page is cut at the oldest instant every bounded source still covers (the
 * horizon) and the cursor is placed there.</p>
 *
 * <p><b>Masking.</b> The aiview persona gets device pseudonyms, keyed {@code USR-} principal tokens and the
 * system-log masking rules on free text, applied here; the response carries {@code masked} so the body advice does
 * not mask twice.</p>
 */
public final class ChangeFeedService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    public static final Duration DEFAULT_RANGE = Duration.ofHours(24);
    public static final Duration MAX_RANGE = Duration.ofDays(31);
    /** A chunk collected at the end of a job window holds lines from the window before it. */
    static final Duration CHUNK_SLACK = Duration.ofHours(6);
    /** Chunks read per source per request; more is PARTIAL with a note. */
    static final int CHUNK_CAP = 400;
    static final int JOB_CAP = 5000;
    static final int ROW_CAP = 2000;
    /** Identical jobs submitted in one hour fold into one line from this count on. */
    static final int FOLD_FROM = 3;
    private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Set<String> VENDORS = Set.of("check_point", "palo_alto");
    private static final DateTimeFormatter STORED_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    static final String ORDERING_NOTE = "Device-clock times (DEVICE_LOCAL) are shown as the device wrote them and ordered "
            + "as Europe/Istanbul; no device prints a zone.";

    /** The request as the route and the CLI pass it: ISO instants or empty, kinds as a comma list or empty for all. */
    public record Request(String from, String to, String kinds, String vendor, String device, String cursor, int limit) {
    }

    /** Which sources the caller's role may read; the route decides (system logs need {@code system_log_read}). */
    public record Permissions(boolean systemLogs) {
    }

    /** Why a request was refused: the HTTP status, a stable code and a sentence. */
    public record Refusal(int status, String code, String message) {
    }

    public static final class RefusedException extends RuntimeException {
        public final Refusal refusal;

        RefusedException(int status, String code, String message) {
            super(code);
            this.refusal = new Refusal(status, code, message);
        }
    }

    record Cursor(Instant boundary, Set<String> keys) {
    }

    /** One source's answer: its events, whether its bound was hit (and the oldest instant it still covers), its note. */
    record SourceResult(String source, Kind kind, List<ChangeEvent> events, boolean boundHit, Instant oldest, String state, String note) {
        static SourceResult of(String source, Kind kind, List<ChangeEvent> events, boolean boundHit, String note) {
            Instant oldest = events.stream().map(ChangeEvent::time).min(Comparator.naturalOrder()).orElse(null);
            return new SourceResult(source, kind, events, boundHit, oldest, boundHit ? "PARTIAL" : "OK", note);
        }

        static SourceResult absent(String source, Kind kind, String state, String note) {
            return new SourceResult(source, kind, List.of(), false, null, state, note);
        }
    }

    private final ChangeFeedRepository repository;
    private final PolicyChangesService policy;
    private final ChunkTextReader chunks;
    private final Supplier<DefaultProjection> plain;
    private final Supplier<DefaultProjection> masked;

    /**
     * @param plain  builds the plain projection for one request
     * @param masked builds the aiview projection for one request (names snapshot and masking session)
     */
    public ChangeFeedService(ChangeFeedRepository repository, PolicyChangesService policy, ChunkTextReader chunks,
            Supplier<DefaultProjection> plain, Supplier<DefaultProjection> masked) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.chunks = Objects.requireNonNull(chunks, "chunks");
        this.plain = Objects.requireNonNull(plain, "plain");
        this.masked = Objects.requireNonNull(masked, "masked");
    }

    // ---------------------------------------------------------------- request

    record Window(Instant from, Instant to, Instant upper, Set<Kind> kinds, Optional<String> vendor, Optional<String> device,
            Cursor cursor, int limit) {
    }

    Window window(Request request, Instant now) {
        int limit = request.limit() <= 0 ? DEFAULT_LIMIT : request.limit();
        if (limit > MAX_LIMIT) throw new RefusedException(400, "LIMIT_INVALID", "limit must be 1.." + MAX_LIMIT + ".");
        Instant to = parseInstant(request.to(), "to").orElse(now);
        Instant from = parseInstant(request.from(), "from").orElse(to.minus(DEFAULT_RANGE));
        if (!from.isBefore(to)) throw new RefusedException(400, "RANGE_INVALID", "from must be before to.");
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new RefusedException(400, "RANGE_TOO_LONG", "The range may span at most " + MAX_RANGE.toDays() + " days.");
        }
        Set<Kind> kinds = EnumSet.allOf(Kind.class);
        if (request.kinds() != null && !request.kinds().isBlank()) {
            kinds = EnumSet.noneOf(Kind.class);
            for (String word : request.kinds().split(",")) {
                String w = word.strip().toUpperCase(Locale.ROOT);
                if (w.isEmpty()) continue;
                try {
                    kinds.add(Kind.valueOf(w));
                } catch (IllegalArgumentException e) {
                    throw new RefusedException(400, "KIND_INVALID", "kinds is a comma list of USER, NEXUS_JOB, DEVICE_EVENT.");
                }
            }
            if (kinds.isEmpty()) kinds = EnumSet.allOf(Kind.class);
        }
        Optional<String> vendor = Optional.ofNullable(request.vendor()).map(String::strip).filter(s -> !s.isEmpty());
        if (vendor.isPresent() && !VENDORS.contains(vendor.get())) {
            throw new RefusedException(400, "VENDOR_INVALID", "vendor is check_point or palo_alto.");
        }
        Optional<String> device = Optional.ofNullable(request.device()).map(String::strip).filter(s -> !s.isEmpty());
        if (device.isPresent() && !DEVICE_ID.matcher(device.get()).matches()) {
            throw new RefusedException(400, "DEVICE_ID_INVALID", "The device id is not a valid opaque identifier.");
        }
        Cursor cursor = parseCursor(request.cursor());
        Instant upper = to;
        if (cursor != null) {
            if (cursor.boundary().isBefore(from) || !cursor.boundary().isBefore(to)) {
                throw new RefusedException(400, "CURSOR_INVALID", "The cursor does not belong to this range.");
            }
            upper = cursor.boundary().plusSeconds(1);
            if (upper.isAfter(to)) upper = to;
        }
        return new Window(from, to, upper, kinds, vendor, device, cursor, limit);
    }

    private static Optional<Instant> parseInstant(String text, String name) {
        if (text == null || text.isBlank()) return Optional.empty();
        try {
            return Optional.of(Instant.parse(text.strip()));
        } catch (DateTimeParseException e) {
            throw new RefusedException(400, "TIME_INVALID", name + " must be an ISO-8601 instant.");
        }
    }

    static Cursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        if (cursor.length() > 8192) throw new RefusedException(400, "CURSOR_INVALID", "The cursor is too long.");
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            Instant boundary = Instant.parse(parts[0]);
            Set<String> keys = new LinkedHashSet<>();
            for (String key : parts[1].split(",")) {
                if (!key.isEmpty()) keys.add(key);
            }
            return new Cursor(boundary, keys);
        } catch (RuntimeException e) {
            throw new RefusedException(400, "CURSOR_INVALID", "The cursor is not one this route returned.");
        }
    }

    static String encodeCursor(Cursor cursor) {
        String text = cursor.boundary() + "|" + String.join(",", cursor.keys());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- the feed

    public ChangeFeedResponse feed(Request request, boolean maskedView, Permissions permissions) {
        return feed(request, maskedView, permissions, Instant.now());
    }

    public ChangeFeedResponse feed(Request request, boolean maskedView, Permissions permissions, Instant now) {
        Window w = window(request, now);
        DefaultProjection projection = maskedView ? masked.get() : plain.get();
        List<SourceResult> results = new ArrayList<>();
        if (w.kinds().contains(Kind.USER)) {
            results.add(guard("policy_rule_history", Kind.USER, () -> policyHistory(w, projection)));
            results.add(guard("pan_config", Kind.USER, () -> panConfig(w, projection, permissions)));
            results.add(guard("device_policy_install", Kind.USER, () -> policyInstalls(w, projection)));
            results.add(guard("policy_change_evidence", Kind.USER, () -> evidence(w, projection)));
        }
        if (w.kinds().contains(Kind.NEXUS_JOB)) {
            results.add(guard("jobs", Kind.NEXUS_JOB, () -> jobs(w, projection)));
        }
        if (w.kinds().contains(Kind.DEVICE_EVENT)) {
            results.add(guard("cp_cpwd_elg", Kind.DEVICE_EVENT, () -> cpwd(w, projection, permissions)));
            results.add(guard("device_crash_alert", Kind.DEVICE_EVENT, () -> crashAlerts(w, projection)));
        }
        return assemble(w, projection, results);
    }

    private static SourceResult guard(String source, Kind kind, Supplier<SourceResult> read) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            // A failing source is UNKNOWN for this page, never "no changes" and never a failed page.
            return SourceResult.absent(source, kind, "UNAVAILABLE", "read failed: " + e.getClass().getSimpleName());
        }
    }

    ChangeFeedResponse assemble(Window w, DefaultProjection projection, List<SourceResult> results) {
        List<ChangeEvent> all = new ArrayList<>();
        Instant horizon = null;
        for (SourceResult r : results) {
            for (ChangeEvent e : r.events()) {
                if (!inWindow(e, w)) continue;
                if (w.vendor().isPresent() && !w.vendor().get().equals(e.vendor())) continue;
                if (w.device().isPresent() && !w.device().get().equals(e.deviceId())) continue;
                all.add(e);
            }
            if (r.boundHit() && r.oldest() != null && (horizon == null || r.oldest().isAfter(horizon))) horizon = r.oldest();
        }
        all.sort(Comparator.comparing(ChangeEvent::time).thenComparing(ChangeEvent::key).reversed());
        List<ChangeEvent> page = new ArrayList<>();
        boolean remaining = false;
        for (ChangeEvent e : all) {
            // Below the horizon a bounded source may hold events this read did not fetch: the page stops there.
            if (horizon != null && e.time().isBefore(horizon)) {
                remaining = true;
                break;
            }
            if (page.size() >= w.limit()) {
                remaining = true;
                break;
            }
            page.add(e);
        }
        boolean more = remaining || (horizon != null && horizon.isAfter(w.from()));
        String nextCursor = "";
        if (more) {
            Instant boundary = page.isEmpty() ? (horizon != null ? horizon : w.from()) : page.get(page.size() - 1).time();
            Set<String> keys = new LinkedHashSet<>();
            if (w.cursor() != null && w.cursor().boundary().equals(boundary)) keys.addAll(w.cursor().keys());
            for (ChangeEvent e : page) {
                if (e.time().equals(boundary)) keys.add(e.key());
            }
            Cursor next = new Cursor(boundary, keys);
            // No progress is possible when the page is empty and the cursor would repeat itself: stop rather than loop.
            boolean stuck = page.isEmpty() && w.cursor() != null && w.cursor().boundary().equals(boundary)
                    && w.cursor().keys().equals(keys);
            if (!stuck) nextCursor = encodeCursor(next);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", page.stream().map(ChangeEvent::toJson).toList());
        body.put("next_cursor", nextCursor);
        body.put("range_start", w.from().toString());
        body.put("range_end", w.to().toString());
        body.put("kinds", w.kinds().stream().map(Enum::name).toList());
        body.put("vendor", w.vendor().orElse(null));
        body.put("device_id", w.device().orElse(null));
        body.put("masked", projection.masked());
        body.put("ordering_note", ORDERING_NOTE);
        List<Map<String, Object>> coverage = new ArrayList<>();
        for (SourceResult r : results) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("source", r.source());
            c.put("kind", r.kind().name());
            c.put("state", r.state());
            c.put("note", r.note());
            c.put("events", r.events().size());
            coverage.add(c);
        }
        body.put("coverage", coverage);
        return new ChangeFeedResponse(projection.masked(), body);
    }

    private static boolean inWindow(ChangeEvent e, Window w) {
        if (e.time().isBefore(w.from()) || !e.time().isBefore(w.to())) return false;
        if (w.cursor() == null) return true;
        Instant b = w.cursor().boundary();
        if (e.time().isAfter(b)) return false;
        return !(e.time().equals(b) && w.cursor().keys().contains(e.key()));
    }

    // ---------------------------------------------------------------- sources

    SourceResult policyHistory(Window w, DefaultProjection p) {
        List<Map<String, Object>> rows = policy.recent(w.from(), w.upper(), w.device().orElse(""), p.masked());
        List<ChangeEvent> events = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Instant time;
            try {
                time = Instant.parse(String.valueOf(row.get("changedOn")));
            } catch (DateTimeParseException e) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> targets = row.get("targets") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
            String deviceId = targets.isEmpty() ? null : String.valueOf(targets.get(0).get("deviceId"));
            String deviceName = targets.isEmpty() ? "Unassigned"
                    : String.join(", ", targets.stream().map(t -> String.valueOf(t.get("name"))).toList());
            String changeType = String.valueOf(row.get("changeType"));
            String name = String.valueOf(row.getOrDefault("name", "UNKNOWN"));
            Object number = row.get("number");
            String what = changeType + " rule " + (number == null ? "?" : number) + " · " + name + " · policy " + row.get("policyName");
            List<ChangeEvent.DiffLine> diff = new ArrayList<>();
            if (row.get("changes") instanceof List<?> changes) {
                for (Object c : changes) {
                    if (!(c instanceof Map<?, ?> m)) continue;
                    String field = String.valueOf(m.get("field"));
                    if (m.get("before") != null) diff.add(new ChangeEvent.DiffLine("-", field + ": " + json(m.get("before"))));
                    if (m.get("after") != null) diff.add(new ChangeEvent.DiffLine("+", field + ": " + json(m.get("after"))));
                }
            }
            boolean observed = Boolean.TRUE.equals(row.get("observedAtCollection"));
            String vendor = deviceId == null ? null : p.vendor(deviceId);
            events.add(new ChangeEvent("policy:" + row.get("revision"), Kind.USER, "policy_rule_history", time, "UTC", null, vendor,
                    deviceId, deviceName, String.valueOf(row.getOrDefault("changedBy", PolicyChangesService.UNKNOWN_ACTOR)), what,
                    observed ? "time observed at collection, not the change time" : null,
                    "removed".equals(changeType) ? Tone.WARN : Tone.NEUTRAL, diff,
                    new Links(deviceId == null ? null : PanConfigEvents.deviceLink(deviceId), String.valueOf(row.get("policyId")),
                            String.valueOf(row.get("ruleId")), null, null)));
        }
        return SourceResult.of("policy_rule_history", Kind.USER, events, rows.size() >= PolicyChangesService.RECENT_BATCH,
                "stored rule revisions with field diffs; the actor is the vendor's last-modifier where stored, else UNKNOWN");
    }

    SourceResult panConfig(Window w, DefaultProjection p, Permissions permissions) {
        return chunkSource("pan_config", Kind.USER, w, p, permissions, "palo_alto",
                (chunk, lines) -> PanConfigEvents.parse(lines, chunk.deviceId(), chunk.chunkId(), p).events());
    }

    SourceResult cpwd(Window w, DefaultProjection p, Permissions permissions) {
        return chunkSource("cp_cpwd_elg", Kind.DEVICE_EVENT, w, p, permissions, "check_point",
                (chunk, lines) -> CpwdEvents.parse(lines, chunk.deviceId(), chunk.chunkId(), chunk.collectedAt(), p).events());
    }

    private interface ChunkParser {
        List<ChangeEvent> parse(ChunkRow chunk, List<String> lines);
    }

    private SourceResult chunkSource(String source, Kind kind, Window w, DefaultProjection p, Permissions permissions, String vendor,
            ChunkParser parser) {
        if (!permissions.systemLogs()) {
            return SourceResult.absent(source, kind, "NOT_PERMITTED", "needs system_log_read");
        }
        if (w.vendor().isPresent() && !w.vendor().get().equals(vendor)) {
            return SourceResult.absent(source, kind, "OK", "not read: vendor filter");
        }
        if (!chunks.available()) {
            return SourceResult.absent(source, kind, "UNAVAILABLE", "SYSTEM_LOG_STORE_UNAVAILABLE: no artefact store mounted");
        }
        if (!p.textAvailable()) {
            return SourceResult.absent(source, kind, "UNAVAILABLE", "SYSTEM_LOG_MASKING_UNAVAILABLE: " + p.textUnavailableReason());
        }
        List<ChunkRow> rows = repository.chunks(source, w.from(), w.upper().plus(CHUNK_SLACK), w.device(), CHUNK_CAP + 1);
        boolean capped = rows.size() > CHUNK_CAP;
        if (capped) rows = rows.subList(0, CHUNK_CAP);
        List<ChangeEvent> events = new ArrayList<>();
        int skipped = 0;
        for (ChunkRow chunk : rows) {
            try {
                events.addAll(parser.parse(chunk, chunks.lines(chunk)));
            } catch (java.io.IOException | IllegalStateException e) {
                skipped++;
            }
        }
        String note = rows.size() + " chunk" + (rows.size() == 1 ? "" : "s") + " read"
                + (skipped > 0 ? ", " + skipped + " unreadable" : "")
                + (capped ? ", more than " + CHUNK_CAP + " in range: narrow the range" : "")
                + "; lines are the device's own text, redacted on write";
        SourceResult result = SourceResult.of(source, kind, events, capped, note);
        if (skipped > 0 && !capped) return new SourceResult(source, kind, result.events(), false, result.oldest(), "PARTIAL", note);
        return result;
    }

    SourceResult policyInstalls(Window w, DefaultProjection p) {
        List<PolicyInstallRow> rows = repository.policyInstalls(w.from(), w.upper(), w.device(), ROW_CAP + 1);
        boolean capped = rows.size() > ROW_CAP;
        List<ChangeEvent> events = new ArrayList<>();
        for (PolicyInstallRow row : capped ? rows.subList(0, ROW_CAP) : rows) {
            String policyName = row.policyName() == null || row.policyName().isBlank() ? "UNKNOWN" : p.policyName(row.policyName());
            events.add(new ChangeEvent("install:" + row.deviceId() + ":" + row.installedAt().getEpochSecond(), Kind.USER,
                    "device_policy_install", row.installedAt(), "UTC", null, p.vendor(row.deviceId()), row.deviceId(),
                    p.device(row.deviceId()), "UNKNOWN", "policy installed · " + policyName + " (as the device reports)",
                    "read by " + row.sourceRead() + " · observed " + row.observedAt(), Tone.NEUTRAL, List.of(),
                    new Links(PanConfigEvents.deviceLink(row.deviceId()), null, null, null, null)));
        }
        return SourceResult.of("device_policy_install", Kind.USER, events, capped,
                "the last install each gateway reports (nightly read); no actor is stored; earlier installs are overwritten");
    }

    SourceResult evidence(Window w, DefaultProjection p) {
        List<EvidenceRow> rows = repository.evidence(w.from(), w.upper(), ROW_CAP + 1);
        boolean capped = rows.size() > ROW_CAP;
        List<ChangeEvent> events = new ArrayList<>();
        for (EvidenceRow row : capped ? rows.subList(0, ROW_CAP) : rows) {
            Instant time;
            String basis;
            String deviceTime = null;
            if (row.changeTime() == null || row.timeBasis() == null) {
                time = row.observedAt();
                basis = "UTC";
            } else {
                LocalDateTime local;
                try {
                    local = LocalDateTime.parse(row.changeTime(), STORED_TIME);
                } catch (DateTimeParseException e) {
                    continue;
                }
                if ("UTC".equals(row.timeBasis())) {
                    time = local.toInstant(ZoneOffset.UTC);
                    basis = "UTC";
                } else {
                    time = local.atZone(PanConfigEvents.ORDERING_ZONE).toInstant();
                    basis = "DEVICE_LOCAL";
                    deviceTime = row.changeTime();
                }
            }
            String actor = adminWords(row.adminRef());
            boolean cp = "CP_SHOW_CHANGES".equals(row.evidenceKind());
            String what = cp
                    ? "published session · " + count(row.addedCount()) + " added · " + count(row.modifiedCount()) + " modified · "
                            + count(row.deletedCount()) + " deleted" + (row.objectChangesTruncated() ? " (list cut)" : "")
                    : (row.commandClass() == null ? "config command UNKNOWN" : row.commandClass()) + " (Panorama config log)"
                            + (row.resultClass() == null ? "" : " · result " + row.resultClass());
            events.add(new ChangeEvent("evidence:" + row.evidenceId(), Kind.USER, "policy_change_evidence", time, basis, deviceTime,
                    row.vendor(), row.sourceId(), p.device(row.sourceId()), actor, what,
                    row.containerRef() == null ? null : "container " + row.containerRef(), Tone.NEUTRAL, List.of(),
                    new Links(null, null, null, row.jobId(), null)));
        }
        long cpStored = repository.evidenceCount("CP_SHOW_CHANGES");
        long panStored = repository.evidenceCount("PAN_CONFIG_LOG");
        String note = (cpStored == 0 ? "Check Point show-changes: no evidence stored (the V163 reader is off by default)"
                : "Check Point show-changes: " + cpStored + " rows stored")
                + "; " + (panStored == 0 ? "Panorama config log: no evidence stored (the V163 reader is off by default)"
                : "Panorama config log: " + panStored + " rows stored")
                + "; admins are stored as keyed references, never names";
        SourceResult result = SourceResult.of("policy_change_evidence", Kind.USER, events, capped, note);
        if (cpStored == 0 && panStored == 0) return new SourceResult(result.source(), result.kind(), result.events(), false, null, "OFF", note);
        return result;
    }

    static String adminWords(String adminRef) {
        if (adminRef == null || adminRef.isBlank() || "UNKNOWN".equals(adminRef)) return "UNKNOWN";
        if (adminRef.startsWith("masked:")) return "admin (masked)";
        return "admin ref " + (adminRef.length() > 8 ? adminRef.substring(0, 8) : adminRef);
    }

    private static String count(Integer n) {
        return n == null ? "UNKNOWN" : String.valueOf(n);
    }

    SourceResult jobs(Window w, DefaultProjection p) {
        List<JobRow> rows = repository.jobs(w.from(), w.upper(), w.device(), JOB_CAP + 1);
        boolean capped = rows.size() > JOB_CAP;
        if (capped) rows = rows.subList(0, JOB_CAP);
        // Fold: identical job types submitted in the same hour, three or more, become one line with their outcome counts.
        Map<String, List<JobRow>> groups = new LinkedHashMap<>();
        for (JobRow row : rows) {
            if (w.vendor().isPresent() && !w.vendor().get().equals(p.vendor(row.targetDeviceId()))) continue;
            String bucket = row.jobType() + "|" + row.submittedAt().getEpochSecond() / 3600;
            groups.computeIfAbsent(bucket, k -> new ArrayList<>()).add(row);
        }
        List<ChangeEvent> events = new ArrayList<>();
        for (List<JobRow> group : groups.values()) {
            if (group.size() >= FOLD_FROM && w.device().isEmpty()) {
                events.add(folded(group, p));
            } else {
                for (JobRow row : group) events.add(single(row, p));
            }
        }
        return SourceResult.of("jobs", Kind.NEXUS_JOB, events, capped,
                "neXus's own runs (collections, backups, readiness, failover); " + FOLD_FROM + " or more identical jobs in one hour fold into one line");
    }

    private static ChangeEvent folded(List<JobRow> group, DefaultProjection p) {
        Map<String, Integer> states = new TreeMap<>();
        Set<String> devices = new LinkedHashSet<>();
        Instant first = null;
        for (JobRow row : group) {
            states.merge(row.state() == null ? "UNKNOWN" : row.state(), 1, Integer::sum);
            if (row.targetDeviceId() != null) devices.add(row.targetDeviceId());
            if (first == null || row.submittedAt().isBefore(first)) first = row.submittedAt();
        }
        int failed = states.getOrDefault("FAILED", 0);
        int unknown = states.getOrDefault("OUTCOME_UNKNOWN", 0);
        int running = states.getOrDefault("REQUESTED", 0) + states.getOrDefault("CLAIMED", 0) + states.getOrDefault("EXECUTING", 0);
        StringBuilder what = new StringBuilder(group.size() + " jobs · " + devices.size() + " device" + (devices.size() == 1 ? "" : "s"));
        for (Map.Entry<String, Integer> e : states.entrySet()) {
            what.append(" · ").append(e.getValue()).append(' ').append(e.getKey().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        Tone tone = failed > 0 ? Tone.WARN : unknown > 0 ? Tone.UNKNOWN : running > 0 ? Tone.NEUTRAL : Tone.OK;
        String type = group.get(0).jobType();
        return new ChangeEvent("jobs:" + type + ":" + first.getEpochSecond(), Kind.NEXUS_JOB, "jobs", first, "UTC", null,
                vendorOf(group, p), null, null, "neXus", what.toString(), "job type " + type, tone, List.of(),
                new Links(null, null, null, null, type));
    }

    private static String vendorOf(List<JobRow> group, DefaultProjection p) {
        String vendor = null;
        for (JobRow row : group) {
            String v = p.vendor(row.targetDeviceId());
            if (v == null) continue;
            if (vendor == null) vendor = v;
            else if (!vendor.equals(v)) return null;
        }
        return vendor;
    }

    private static ChangeEvent single(JobRow row, DefaultProjection p) {
        String state = row.state() == null ? "UNKNOWN" : row.state();
        Tone tone = switch (state) {
            case "COMPLETED" -> Tone.OK;
            case "FAILED" -> Tone.BAD;
            case "OUTCOME_UNKNOWN" -> Tone.UNKNOWN;
            case "CANCELLED", "REJECTED" -> Tone.WARN;
            default -> Tone.NEUTRAL;
        };
        String what = state.toLowerCase(Locale.ROOT).replace('_', ' ') + " · job type " + row.jobType()
                + (row.outcome() == null || row.outcome().isBlank() || row.outcome().equalsIgnoreCase(state) ? "" : " · outcome " + row.outcome());
        String detail = row.terminalReason() == null || row.terminalReason().isBlank() ? null : p.text(row.terminalReason());
        String deviceName = row.targetDeviceId() == null ? (row.targetRef() == null ? null : row.targetKind() + " target") : p.device(row.targetDeviceId());
        return new ChangeEvent("job:" + row.jobId(), Kind.NEXUS_JOB, "jobs", row.submittedAt(), "UTC", null,
                p.vendor(row.targetDeviceId()), row.targetDeviceId(), deviceName, "neXus", what, detail, tone, List.of(),
                new Links(row.targetDeviceId() == null ? null : PanConfigEvents.deviceLink(row.targetDeviceId()), null, null, row.jobId(), null));
    }

    SourceResult crashAlerts(Window w, DefaultProjection p) {
        List<CrashAlertRow> rows = repository.crashAlerts(w.from(), w.upper(), w.device(), ROW_CAP + 1);
        boolean capped = rows.size() > ROW_CAP;
        List<ChangeEvent> events = new ArrayList<>();
        for (CrashAlertRow row : capped ? rows.subList(0, ROW_CAP) : rows) {
            String process = row.process() == null || row.process().isBlank() ? "UNKNOWN" : p.text(row.process());
            events.add(new ChangeEvent("crash:" + row.alertId(), Kind.DEVICE_EVENT, "device_crash_alert", row.createdAt(), "UTC", null,
                    p.vendor(row.deviceId()), row.deviceId(), p.device(row.deviceId()), "device",
                    "new crash or dump file" + (row.fileCount() == 1 ? "" : "s") + " · process " + process + " · " + row.fileCount(),
                    "newest file mtime (device epoch) " + row.newestMtime(), Tone.BAD, List.of(),
                    new Links(PanConfigEvents.deviceLink(row.deviceId()), null, null, row.jobId(), null)));
        }
        return SourceResult.of("device_crash_alert", Kind.DEVICE_EVENT, events, capped,
                "one alert per device, run and process when the crash/dump listing shows a new file");
    }

    private static String json(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
