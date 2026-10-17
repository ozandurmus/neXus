package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.policy.PolicyCompletenessGates;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeEvidenceRepository;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeEvidenceRepository.Cursor;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeEvidenceRepository.Evidence;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.worker.discovery.pan.PanoramaApiRoutes;
import com.securityexpert.nexus.ui2.worker.policy.PolicyCompletenessSettings;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;

/**
 * Panorama config-log reader for policy change evidence (PO approval 2026-10-18; plan
 * docs/design/POLICY_COLLECTION_COMPLETENESS_PLAN.md section 6, commands P-2, P-3, P-4): "who changed what, when", read
 * incrementally by {@code receive_time} cursor. It is the Panorama counterpart of the firewall config-log half of
 * {@link PanSystemLogCollectJobExecutor} and reuses its page parser ({@link PanLogPage}), its secret redaction
 * ({@link PanLogRedactor}, rule set {@value PanLogRedactor#RULE_SET_ID}) and its cursor semantics (the newest second plus
 * the seqnos already stored at that second); what differs is the target (Panorama, scope {@code panorama}), the gate rows
 * (V163) and what is kept: the raw entries are discarded after parsing and only evidence rows are stored.
 *
 * <ul>
 *   <li><b>Off by default (partial-first).</b> It reads only the one Panorama named by
 *       {@code UI2_POLICY_PAN_CONFIG_LOG_TRIAL_SOURCE}; every other source returns {@code OFF} without a call.</li>
 *   <li><b>Window and admission.</b> It runs inside a policy job, which the claim path admits through
 *       {@link JobWindowPolicy}; it also refuses to start when the job started outside the window, and it shares the
 *       existing six-hour {@code policy_collection_domain} throttle for automatic jobs.</li>
 *   <li><b>Bounds (plan section 6).</b> P-3 seed once per Panorama; P-2 at most {@value PolicyCompletenessGates#PAN_LOG_MAX_PAGES}
 *       pages per run, {@code <n>} 1 to 5000, 8 MiB per page; P-4 every 2 s, at most 30 polls per enqueued job; timeout
 *       30 s per call, no retry. The API key is the policy job's own, passed in; calls are serial.</li>
 *   <li><b>Fail closed.</b> An API error, an unfinished log job or an unreadable answer is {@code UNKNOWN} with the cursor
 *       unchanged and nothing stored; the next window is the retry. The reader never throws for those, so the policy
 *       collection is not affected.</li>
 *   <li><b>What is stored.</b> Per entry: the seqno, the receive time (device-local, never converted), the admin as an HMAC
 *       (or {@code masked:<length>} when no fingerprint key is mounted), a shape-checked command class and result class, an
 *       HMAC of the path, the opaque device-group ref when the path names one, and the redaction count.
 *       {@code before-/after-change-detail}, names and raw text are never stored.</li>
 * </ul>
 *
 * <p><b>UNKNOWN (plan section 8), not resolved here:</b> the Panorama config-log field content for device-group changes.
 * The field names used below ({@code admin}, {@code cmd}, {@code result}, {@code path}) are the names the firewall
 * config-log measurement saw; whether Panorama writes the same names, and whether {@code path} names the device group, is
 * unmeasured. A field that is absent or not shaped as expected becomes NULL/UNKNOWN in the row, never a guess. Any API rate
 * limit is UNKNOWN as well. Vendor documentation: API Log Retrieval Parameters and Retrieve Logs (see
 * {@link PolicyCompletenessGates}).</p>
 */
public final class PanoramaConfigLogReader {
    @FunctionalInterface public interface Pause { void sleep(Duration duration) throws InterruptedException; }

    /** P-3: the first run reads the newest 100 entries (as the firewall config-log seed). */
    static final int SEED_ENTRIES = 100;
    /** P-2: entries per forward page, within the 1 to 5000 of the approved text. */
    static final int PAGE_SIZE = 1000;
    private static final String DOMAIN_ADMIN = "policy-change-admin-v1";
    private static final String DOMAIN_PATH = "policy-change-path-v1";
    private static final DateTimeFormatter PAN_TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern COMMAND_CLASS = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
    private static final Pattern RESULT_CLASS = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,31}");
    private static final Pattern DEVICE_GROUP_PATH = Pattern.compile("/device-group/entry\\[@name=(?:'([^'\\r\\n]{1,255})'|\"([^\"\\r\\n]{1,255})\")\\]");
    private static final Comparator<String> SEQNO_ORDER = Comparator.comparing(java.math.BigInteger::new);

    /** @param outcome OFF, COLLECTED, NO_NEW_DATA, BACKLOG or UNKNOWN; {@code reason} is a fixed code for UNKNOWN. */
    public record Result(String outcome, String reason, int pages, int entries, int stored, long resultBytes, long enqueueToFinMs) {
        static Result off() { return new Result("OFF", null, 0, 0, 0, 0, 0); }
        static Result unknown(String reason, int pages, long bytes, long ms) { return new Result("UNKNOWN", reason, pages, 0, 0, bytes, ms); }
        /** The safe telemetry of plan section 6 (P-2: entries, resultBytes, enqueueToFinMs, pages) and nothing else. */
        public String note() {
            return "PAN_CONFIG_LOG outcome=" + outcome + (reason == null ? "" : " reason=" + reason) + " entries=" + entries
                    + " resultBytes=" + resultBytes + " enqueueToFinMs=" + enqueueToFinMs + " pages=" + pages;
        }
    }

    /** A typed, per-run failure: becomes an UNKNOWN result; the cursor and the evidence are untouched. */
    private static final class Unknown extends RuntimeException {
        final String code;
        Unknown(String code) { super(code, null, false, false); this.code = code; }
    }

    private final DeviceTransport transport;
    private final GateRegistryPort gates;
    private final PolicyChangeEvidenceRepository evidence;
    private final PolicyCollectionRepository policies;
    private final PolicyCompletenessSettings settings;
    private final JobWindowPolicy windows;
    private final HostnameFingerprint fingerprint;
    private final Pause pause;

    /** @param fingerprint the product HMAC key holder, or null when the policy pod has no key: the admin is then masked. */
    public PanoramaConfigLogReader(DeviceTransport transport, GateRegistryPort gates, PolicyChangeEvidenceRepository evidence,
            PolicyCollectionRepository policies, PolicyCompletenessSettings settings, JobWindowPolicy windows,
            HostnameFingerprint fingerprint) {
        this(transport, gates, evidence, policies, settings, windows, fingerprint, d -> Thread.sleep(d.toMillis()));
    }

    public PanoramaConfigLogReader(DeviceTransport transport, GateRegistryPort gates, PolicyChangeEvidenceRepository evidence,
            PolicyCollectionRepository policies, PolicyCompletenessSettings settings, JobWindowPolicy windows,
            HostnameFingerprint fingerprint, Pause pause) {
        this.transport = transport; this.gates = gates; this.evidence = evidence; this.policies = policies;
        this.settings = settings; this.windows = windows; this.fingerprint = fingerprint; this.pause = pause;
    }

    /**
     * Reads one run's worth of the trial Panorama's config log and stores its evidence.
     *
     * @param jobStart when the policy job began; the run is refused if that was outside the job window
     * @param key the policy job's Panorama API key (never logged, never stored)
     */
    public Result read(String sourceId, String jobId, boolean automatic, Instant jobStart, ApiTarget target, char[] key,
            BooleanSupplier lease) {
        if (!settings.panConfigLogTargets(sourceId)) return Result.off();
        if (!windows.isOpen(jobStart)) return Result.unknown("OUTSIDE_JOB_WINDOW", 0, 0, 0);
        if (!PolicyCompletenessGates.panConfigLogEnabled(gates)) return Result.unknown("POLICY_GATE_UNAVAILABLE", 0, 0, 0);
        if (!policies.beginDomain(sourceId, PolicySnapshot.ref("pan-config-log"), automatic)) return Result.unknown("THROTTLED", 0, 0, 0);
        long[] bytes = {0};
        long[] latency = {0};
        int[] pages = {0};
        try {
            Result result = collect(sourceId, jobId, target, new String(key), lease, bytes, latency, pages);
            JobTranscriptScope.add("job", "note", result.note());
            return result;
        } catch (Unknown unknown) {
            Result result = Result.unknown(unknown.code, pages[0], bytes[0], latency[0]);
            JobTranscriptScope.add("job", "note", result.note());
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.unknown("INTERRUPTED", pages[0], bytes[0], latency[0]);
        }
    }

    private Result collect(String sourceId, String jobId, ApiTarget target, String key, BooleanSupplier lease, long[] bytes,
            long[] latency, int[] pages) throws InterruptedException {
        Optional<Cursor> stored = evidence.cursor(sourceId);
        List<PanLogPage.Entry> fresh = new ArrayList<>();
        Optional<Cursor> newCursor = Optional.empty();
        boolean backlog = false;
        if (stored.isEmpty()) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("log-type", "config");
            params.put("nlogs", String.valueOf(SEED_ENTRIES));
            List<PanLogPage.Entry> page = fetch(PolicyCompletenessGates.P3_CONFIG_LOG_SEED, params, target, key, lease, bytes, latency, pages);
            fresh.addAll(page);
            if (!page.isEmpty()) newCursor = Optional.of(cursorAt(page));
        } else {
            String cursorTime = stored.get().lastReceiveTime();
            TreeSet<String> boundary = new TreeSet<>(SEQNO_ORDER);
            boundary.addAll(stored.get().boundarySeqnos());
            for (int pageNo = 1; pageNo <= PolicyCompletenessGates.PAN_LOG_MAX_PAGES; pageNo++) {
                if (!PolicyCompletenessGates.PAN_TIMESTAMP.matcher(cursorTime).matches()) throw new Unknown("CURSOR_SHAPE_INVALID");
                Map<String, String> params = new LinkedHashMap<>();
                params.put("log-type", "config");
                params.put("nlogs", String.valueOf(PAGE_SIZE));
                params.put("dir", "forward");
                params.put("query", "(receive_time geq '" + cursorTime + "')");
                List<PanLogPage.Entry> raw = fetch(PolicyCompletenessGates.P2_CONFIG_LOG_ENQUEUE, params, target, key, lease, bytes, latency, pages);
                // "geq <cursor>" never answers an older entry. If it does the query was not honoured (dir=forward and the
                // query syntax are vendor semantics not yet proven on Panorama): UNKNOWN, cursor unchanged.
                for (PanLogPage.Entry e : raw) if (e.receiveTime().compareTo(cursorTime) < 0) throw new Unknown("QUERY_NOT_HONORED");
                String newest = raw.stream().map(PanLogPage.Entry::receiveTime).max(String::compareTo).orElse(cursorTime);
                final String boundaryTime = cursorTime;
                final TreeSet<String> seen = boundary;
                for (PanLogPage.Entry e : raw) if (!(e.receiveTime().equals(boundaryTime) && seen.contains(e.seqno()))) fresh.add(e);
                TreeSet<String> atNewest = new TreeSet<>(SEQNO_ORDER);
                for (PanLogPage.Entry e : raw) if (e.receiveTime().equals(newest)) atNewest.add(e.seqno());
                if (newest.equals(cursorTime)) atNewest.addAll(boundary);
                boolean full = raw.size() >= PAGE_SIZE;
                if (full && newest.equals(cursorTime)) {
                    // More than a page inside one second: paging cannot advance. Step over that second (the loss is not
                    // measured here; the run is reported BACKLOG so it is visible, never silently complete).
                    cursorTime = plusOneSecond(cursorTime);
                    boundary = new TreeSet<>(SEQNO_ORDER);
                    newCursor = Optional.of(new Cursor(cursorTime, List.of()));
                    backlog = true;
                    break;
                }
                if (!newest.equals(cursorTime) || !atNewest.equals(boundary)) {
                    cursorTime = newest;
                    boundary = atNewest;
                    newCursor = Optional.of(new Cursor(cursorTime, new ArrayList<>(boundary)));
                }
                if (!full) break;
                if (pageNo == PolicyCompletenessGates.PAN_LOG_MAX_PAGES) backlog = true;
            }
        }
        fresh.sort(Comparator.comparing(PanLogPage.Entry::receiveTime).thenComparing(PanLogPage.Entry::seqno, SEQNO_ORDER));
        List<Evidence> rows = new ArrayList<>();
        for (PanLogPage.Entry entry : fresh) rows.add(evidenceOf(sourceId, entry));
        int storedRows;
        try {
            storedRows = evidence.save(sourceId, jobId, rows, newCursor);
        } catch (RuntimeException notStored) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(notStored);
            throw new Unknown("EVIDENCE_NOT_STORED");
        }
        String outcome = backlog ? "BACKLOG" : fresh.isEmpty() ? "NO_NEW_DATA" : "COLLECTED";
        return new Result(outcome, null, pages[0], fresh.size(), storedRows, bytes[0], latency[0]);
    }

    /** One page: enqueue (P-3 or P-2), bounded poll to FIN (P-4), parse. */
    private List<PanLogPage.Entry> fetch(PolicyCompletenessGates.Command enqueue, Map<String, String> params, ApiTarget target,
            String key, BooleanSupplier lease, long[] bytes, long[] latency, int[] pages) throws InterruptedException {
        String enqueued = call(enqueue, params, target, key, lease);
        long enqueuedAt = System.nanoTime();
        String logJob = PanLogMeasurementSummary.enqueuedJobId(enqueued)
                .filter(id -> PolicyCompletenessGates.PAN_JOB_ID.matcher(id).matches())
                .orElseThrow(() -> new Unknown("LOG_JOB_NOT_ENQUEUED"));
        for (int poll = 1; poll <= PolicyCompletenessGates.PAN_LOG_MAX_POLLS; poll++) {
            pause.sleep(Duration.ofSeconds(PolicyCompletenessGates.PAN_LOG_POLL_SECONDS));
            Map<String, String> getParams = new LinkedHashMap<>();
            getParams.put("action", "get");
            getParams.put("job-id", logJob);
            String answer = call(PolicyCompletenessGates.P4_LOG_JOB_GET, getParams, target, key, lease);
            String status = PanLogMeasurementSummary.jobStatus(answer).orElseThrow(() -> new Unknown("LOG_JOB_STATUS_UNAVAILABLE"));
            if ("FIN".equals(status)) {
                PanLogPage page;
                try {
                    page = PanLogPage.parse(answer);
                } catch (PanLogPage.Unreadable unreadable) {
                    throw new Unknown(unreadable.code);
                }
                pages[0]++;
                bytes[0] += answer.getBytes(StandardCharsets.UTF_8).length;
                latency[0] = Math.max(latency[0], (System.nanoTime() - enqueuedAt) / 1_000_000);
                return page.entries();
            }
        }
        throw new Unknown("LOG_JOB_NOT_FINISHED");
    }

    /** One gated read; an answer that is not an HTTP 200 success within the size bound is UNKNOWN. Never recorded in a transcript. */
    private String call(PolicyCompletenessGates.Command command, Map<String, String> params, ApiTarget target, String key,
            BooleanSupplier lease) {
        com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
        if (!lease.getAsBoolean()) throw new Unknown("LEASE_LOST");
        if (!PolicyCompletenessGates.enabled(gates, command)) throw new Unknown("POLICY_GATE_UNAVAILABLE");
        XmlApiResult result = JobTranscriptScope.withoutRecording(() -> transport.xmlApiCall(target,
                new XmlApiSpec("POST", "log", "", "no_target", params, Map.of(PanoramaApiRoutes.KEY_HEADER_NAME, key)),
                Duration.ofSeconds(command.timeoutS())));
        if (!(result instanceof XmlApiResult.Completed completed)) throw new Unknown("TRANSPORT_FAILED");
        if (completed.httpStatus() != 200 || completed.body() == null) throw new Unknown("API_ERROR");
        if (completed.body().length() > PolicyCompletenessGates.PAN_LOG_MAX_RESULT_CHARS) throw new Unknown("RESULT_TOO_LARGE");
        if (!PanLogMeasurementSummary.success(completed.body())) throw new Unknown("API_ERROR");
        return completed.body();
    }

    /** Redacts, then keeps only the shape-checked evidence fields of one entry. */
    Evidence evidenceOf(String sourceId, PanLogPage.Entry entry) {
        PanLogRedactor.Result redacted = PanLogRedactor.redact("pan_config", entry.fields());
        Map<String, String> fields = redacted.fields();
        String admin = strip(fields.get("admin"));
        String adminRef = admin.isEmpty() ? "UNKNOWN" : fingerprint != null ? fingerprint.ofContent(DOMAIN_ADMIN,
                admin.getBytes(StandardCharsets.UTF_8), 0, admin.getBytes(StandardCharsets.UTF_8).length)
                : "masked:" + Math.min(admin.length(), 999);
        String command = strip(fields.get("cmd")).toLowerCase(java.util.Locale.ROOT);
        String outcome = strip(fields.get("result"));
        String path = strip(fields.get("path"));
        String pathHmac = fingerprint == null || path.isEmpty() ? null : fingerprint.ofContent(DOMAIN_PATH,
                path.getBytes(StandardCharsets.UTF_8), 0, path.getBytes(StandardCharsets.UTF_8).length);
        return new Evidence("palo_alto", PolicyChangeEvidenceRepository.PAN_CONFIG_LOG, containerRef(sourceId, path), entry.seqno(),
                sqlTime(entry.receiveTime()), "DEVICE_LOCAL", adminRef, COMMAND_CLASS.matcher(command).matches() ? command : null,
                RESULT_CLASS.matcher(outcome).matches() ? outcome : null, pathHmac, redacted.redactions(), null, null, null, null, false);
    }

    /** The opaque device-group container ref when the path names one; null (UNKNOWN) otherwise. */
    static String containerRef(String sourceId, String path) {
        Matcher m = DEVICE_GROUP_PATH.matcher(path);
        if (!m.find()) return null;
        String name = m.group(1) != null ? m.group(1) : m.group(2);
        return name.contains(PanLogRedactor.REDACTED) ? null : PolicySnapshot.ref(sourceId, "device-group", name);
    }

    private static String strip(String value) { return value == null ? "" : value.strip(); }

    private static Cursor cursorAt(List<PanLogPage.Entry> entries) {
        String newest = entries.stream().map(PanLogPage.Entry::receiveTime).max(String::compareTo).orElseThrow();
        TreeSet<String> seqnos = new TreeSet<>(SEQNO_ORDER);
        for (PanLogPage.Entry e : entries) if (e.receiveTime().equals(newest)) seqnos.add(e.seqno());
        return new Cursor(newest, new ArrayList<>(seqnos));
    }

    private static String plusOneSecond(String receiveTime) {
        return LocalDateTime.parse(receiveTime, PAN_TIME).plusSeconds(1).format(PAN_TIME);
    }

    private static String sqlTime(String receiveTime) {
        return LocalDateTime.parse(receiveTime, PAN_TIME).format(SQL_TIME);
    }
}
