package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.diagnostic.PanSystemLogCollect;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactValidation;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository.Gap;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository.PanCursor;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver;

/**
 * System-log programme phase B, Palo Alto half (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md sections 3.3, 5, 6, 9, 12; PO
 * approval 2026-10-17): the {@code pan_system_log_collect} job. On one enrolled Palo Alto firewall (never Panorama) it
 * collects the system log and then the config log through the XML API log query, incrementally:
 *
 * <ul>
 *   <li><b>Cursor.</b> The newest {@code receive_time} seen (device-local text, verbatim) plus the {@code seqno}s
 *       stored at exactly that second. A forward page asks {@code receive_time geq <cursor>}; entries of the boundary
 *       second whose seqno is already stored are dropped. Nothing depends on seqno contiguity (UNKNOWN, section 11).</li>
 *   <li><b>First run.</b> The seed query (newest 2000 system / 100 config entries) is stored and the cursor set to its
 *       newest entry. A full seed leaves older entries uncollected and records an {@code INITIAL_BASELINE} gap.</li>
 *   <li><b>Bounds.</b> Page 2000 entries, at most 4 pages per source per run, 8 MiB per result, polling 2 s x 30.
 *       More data than the bound is {@code BACKLOG}; a second holding more than a page is a {@code PAGE_STALL} gap.</li>
 *   <li><b>Storage.</b> One gzip-compressed, envelope-encrypted {@code system_log} artefact per source per run, written
 *       through the shared artefact store, indexed by a {@code system_log_chunk} row. The cursor, the chunk, the gaps and
 *       the run outcome are written in one transaction. Config-log secret fields are masked before storage.</li>
 *   <li><b>Outcomes</b> are exactly the section 9 set. An API error, unfinished log job or unreadable answer is
 *       {@code UNKNOWN} with the cursor unchanged and nothing stored from that source; the next window is the retry.</li>
 * </ul>
 *
 * Device text never reaches a log line, a job transcript (none is opened) or an exception message: only fixed codes do.
 * The raw XML answers are discarded after parsing; the stored copy is the redacted JSON Lines chunk.
 */
public final class PanSystemLogCollectJobExecutor {
    @FunctionalInterface public interface Pause { void sleep(Duration duration) throws InterruptedException; }

    static final String ACTOR = "system:worker";
    static final Duration POLL_INTERVAL = Duration.ofSeconds(PanSystemLogCollect.POLL_INTERVAL_SECONDS);
    static final int MAX_POLLS = PanSystemLogCollect.MAX_POLLS;
    static final int PAGE_SIZE = PanSystemLogCollect.PAGE_SIZE;
    static final int MAX_PAGES = PanSystemLogCollect.MAX_PAGES;
    private static final String VENDOR = "palo_alto";
    private static final Pattern KEY = Pattern.compile("<key>\\s*([^<\\s]+)\\s*</key>");
    private static final Pattern JOB_ID = Pattern.compile("[0-9]{1,20}");
    private static final DateTimeFormatter PAN_TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final java.util.Set<String> CLEAN_OUTCOMES = java.util.Set.of("COLLECTED", "NO_NEW_DATA", "BACKLOG", "GAP",
            "ABSENT", "UNSUPPORTED");

    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DeviceRepository devices;
    private final DeviceTransport transport;
    private final PanCredentialResolver credentials;
    private final GateRegistryPort gates;
    private final SystemLogCollectionRepository repository;
    private final ArtefactStore artefacts;
    private final SystemLogArtefactVolume volume;
    private final HostnameFingerprint fingerprint;
    private final Pause pause;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public PanSystemLogCollectJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceTransport transport, PanCredentialResolver credentials, GateRegistryPort gates,
            SystemLogCollectionRepository repository, ArtefactStore artefacts, SystemLogArtefactVolume volume,
            HostnameFingerprint fingerprint) {
        this(leases, attempts, devices, transport, credentials, gates, repository, artefacts, volume, fingerprint,
                d -> Thread.sleep(d.toMillis()), Clock.systemUTC());
    }

    PanSystemLogCollectJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceTransport transport, PanCredentialResolver credentials, GateRegistryPort gates,
            SystemLogCollectionRepository repository, ArtefactStore artefacts, SystemLogArtefactVolume volume,
            HostnameFingerprint fingerprint, Pause pause, Clock clock) {
        this.leases = leases; this.attempts = attempts; this.devices = devices; this.transport = transport;
        this.credentials = credentials; this.gates = gates; this.repository = repository; this.artefacts = artefacts;
        this.volume = volume; this.fingerprint = fingerprint; this.pause = pause; this.clock = clock;
    }

    /** A step the job could not record or a lease it lost: the job stops; nothing further is sent. */
    private static final class Abort extends RuntimeException {
        final JobState state; final String code;
        Abort(JobState state, String code) { super(code, null, false, false); this.state = state; this.code = code; }
    }

    /** One source's typed failure: recorded as an UNKNOWN run, the next source still runs. */
    private static final class Unknown extends RuntimeException {
        final String code;
        Unknown(String code) { super(code, null, false, false); this.code = code; }
    }

    private static final class Run {
        final String jobId; final long epoch; final String deviceId; final ApiTarget target; int step; int reads;
        Run(String jobId, long epoch, String deviceId, ApiTarget target) {
            this.jobId = jobId; this.epoch = epoch; this.deviceId = deviceId; this.target = target;
        }
    }

    /** The five approved requests, resolved against the live gates once per job. */
    private record Gates(GateResolution.Known systemSeed, GateResolution.Known configSeed,
            GateResolution.Known systemEnqueue, GateResolution.Known configEnqueue, GateResolution.Known get) {
        static Optional<Gates> resolve(GateRegistryPort registry) {
            var a = PanSystemLogCollect.gate(PanSystemLogCollect.SYSTEM_SEED, registry);
            var b = PanSystemLogCollect.gate(PanSystemLogCollect.CONFIG_SEED, registry);
            var c = PanSystemLogCollect.gate(PanSystemLogCollect.SYSTEM_ENQUEUE, registry);
            var d = PanSystemLogCollect.gate(PanSystemLogCollect.CONFIG_ENQUEUE, registry);
            var e = PanSystemLogCollect.gate(PanSystemLogCollect.JOB_GET, registry);
            return a.isPresent() && b.isPresent() && c.isPresent() && d.isPresent() && e.isPresent()
                    ? Optional.of(new Gates(a.get(), b.get(), c.get(), d.get(), e.get())) : Optional.empty();
        }
    }

    /** Page-level numbers kept per page: the first rollout reads {@code enqueueToFinMs} at n = 2000 from here (section 15 item 7). */
    private record PageStat(int entries, long resultBytes, long enqueueToFinMs, int polls) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entries", entries); m.put("result_bytes", resultBytes);
            m.put("enqueue_to_fin_ms", enqueueToFinMs); m.put("polls", polls);
            return m;
        }
    }

    private record Page(List<PanLogPage.Entry> entries, PageStat stat) {
    }

    private record SourceResult(SystemLogCollectionRepository.SourceWrite write, String outcome, String reason) {
    }

    public void execute(String jobId, long epoch, String deviceId) {
        var device = devices.find(deviceId);
        var endpoint = devices.findEndpointByDeviceId(deviceId)
                .filter(e -> deviceId.equals(e.deviceId()) && "pan_xml_api".equalsIgnoreCase(e.transportKind()));
        // Section 2.3 / 3.3: pan_firewall only. A management server (Panorama) is refused, never read, whatever the setting.
        if (device.isPresent() && VENDOR.equals(device.get().vendorHint()) && "management_server".equals(device.get().role())) {
            leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.REJECTED, ACTOR, "syslog_collect_claim_check",
                    "SYSTEM_LOG_DISABLED_ON_MANAGEMENT");
            return;
        }
        Optional<Gates> resolved = Gates.resolve(gates);
        if (device.isEmpty() || !device.get().permitsReadCollection() || !VENDOR.equals(device.get().vendorHint())
                || !"gateway".equals(device.get().role()) || endpoint.isEmpty() || repository == null || artefacts == null
                || resolved.isEmpty()) {
            leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.REJECTED, ACTOR, "syslog_collect_claim_check",
                    "SYSTEM_LOG_COLLECT_UNAVAILABLE");
            return;
        }
        if (!leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR, "syslog_collect_start")) return;
        Run run = new Run(jobId, epoch, deviceId, new ApiTarget(endpoint.get().endpointId(), endpoint.get().addressRef()));
        try {
            if (!attempts.findByJobAndStep(jobId, 0).isEmpty()) throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
            Optional<String> key = apiKey(run.target, device.get().credentialReferenceId());
            int retentionDays = repository.settings().rawRetentionDays();
            String hostnameSource = devices.findSummary(deviceId).flatMap(DeviceSummaryRecord::observedHostname).orElse(deviceId);
            List<String> failures = new ArrayList<>();
            for (String source : List.of(SystemLogCollectionRepository.SOURCE_PAN_SYSTEM, SystemLogCollectionRepository.SOURCE_PAN_CONFIG)) {
                long started = clock.millis();
                SourceResult result;
                if (key.isEmpty()) {
                    result = unknown("AUTH_UNAVAILABLE", started, List.of(), 0, 0);
                } else {
                    result = collect(run, source, resolved.get(), key.get(), retentionDays, hostnameSource, started);
                }
                if (!CLEAN_OUTCOMES.contains(result.outcome())) failures.add(result.reason());
                var chunk = result.write().chunk();
                boolean recorded;
                try {
                    recorded = repository.record(jobId, epoch, deviceId, source, result.write());
                } catch (RuntimeException notRecorded) {
                    chunk.ifPresent(c -> volume.discard(new com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef(
                            c.manifest().recoveryVolumePath())));
                    throw notRecorded;
                }
                if (!recorded) {
                    chunk.ifPresent(c -> volume.discard(new com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef(
                            c.manifest().recoveryVolumePath())));
                    throw new Abort(JobState.OUTCOME_UNKNOWN, "SYSTEM_LOG_NOT_RECORDED");
                }
                run.reads = 0;
            }
            finish(jobId, epoch, failures.isEmpty() ? JobState.COMPLETED : JobState.FAILED,
                    failures.isEmpty() ? "SYSTEM_LOG_COLLECTED" : "partial:" + failures.get(0));
        } catch (Abort abort) {
            finish(jobId, epoch, abort.state, abort.code);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finish(jobId, epoch, JobState.OUTCOME_UNKNOWN, "INTERRUPTED");
        } catch (Exception failure) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(failure);
            finish(jobId, epoch, JobState.OUTCOME_UNKNOWN, "SYSLOG_COLLECT_OUTCOME_UNCERTAIN");
        }
    }

    private SourceResult unknown(String reason, long started, List<PageStat> pages, int reads, long bytesRead) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        detail.put("pages", pages.stream().map(PageStat::toMap).toList());
        return new SourceResult(new SystemLogCollectionRepository.SourceWrite(
                new SystemLogCollectionRepository.Run("UNKNOWN", bytesRead, 0, reads, elapsed(started), detail(detail)),
                Optional.empty(), Optional.empty(), List.of()), "UNKNOWN", reason);
    }

    /** One source of one run; a typed failure becomes an UNKNOWN result that keeps the page numbers read so far. */
    private SourceResult collect(Run run, String source, Gates gates, String key, int retentionDays, String hostnameSource,
            long started) throws InterruptedException {
        List<PageStat> stats = new ArrayList<>();
        long[] bytesRead = {0};
        try {
            return collectPages(run, source, gates, key, retentionDays, hostnameSource, started, stats, bytesRead);
        } catch (Unknown unknown) {
            return unknown(unknown.code, started, stats, run.reads, bytesRead[0]);
        }
    }

    private SourceResult collectPages(Run run, String source, Gates gates, String key, int retentionDays,
            String hostnameSource, long started, List<PageStat> stats, long[] bytesRead) throws InterruptedException {
        boolean system = SystemLogCollectionRepository.SOURCE_PAN_SYSTEM.equals(source);
        String logType = system ? "system" : "config";
        // Section 5.1: no chunk is written (and nothing is asked of the device) below 15 % free space.
        if (volume.freeFraction() < SystemLogArtefactVolume.MIN_FREE_FRACTION) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reason", "ARTEFACT_VOLUME_LOW_SPACE");
            return new SourceResult(new SystemLogCollectionRepository.SourceWrite(new SystemLogCollectionRepository.Run(
                    "STORAGE_REFUSED", 0, 0, 0, elapsed(started), detail(detail)), Optional.empty(), Optional.empty(), List.of()),
                    "STORAGE_REFUSED", "ARTEFACT_VOLUME_LOW_SPACE");
        }
        Optional<PanCursor> stored = repository.findPanCursor(run.deviceId, source);
        List<Gap> gaps = new ArrayList<>();
        List<PanLogPage.Entry> fresh = new ArrayList<>();
        Optional<PanCursor> newCursor = Optional.empty();
        boolean backlog = false;
        boolean seed = stored.isEmpty();
        String previousSeqno = null;

        if (seed) {
            int seedEntries = system ? PanSystemLogCollect.SYSTEM_SEED_ENTRIES : PanSystemLogCollect.CONFIG_SEED_ENTRIES;
            Map<String, String> params = new LinkedHashMap<>();
            params.put("log-type", logType);
            params.put("nlogs", String.valueOf(seedEntries));
            Page page = fetch(run, system ? gates.systemSeed() : gates.configSeed(), gates.get(), params, key, stats);
            bytesRead[0] += page.stat().resultBytes();
            fresh.addAll(page.entries());
            if (!page.entries().isEmpty()) newCursor = Optional.of(cursorAt(page.entries()));
            if (page.entries().size() >= seedEntries) {
                // Older entries exist that the seed deliberately did not take; their number is not known.
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("seed_entries", seedEntries);
                gaps.add(new Gap("INITIAL_BASELINE", 0, 0, true, null, null, detail(detail)));
            }
        } else {
            String cursorTime = stored.get().lastReceiveTime();
            TreeSet<String> boundary = new TreeSet<>(stored.get().boundarySeqnos());
            previousSeqno = boundary.stream().max(SEQNO_ORDER).orElse(null);
            for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("log-type", logType);
                params.put("nlogs", String.valueOf(PAGE_SIZE));
                params.put("dir", "forward");
                params.put("query", "(receive_time geq '" + cursorTime + "')");
                Page page = fetch(run, system ? gates.systemEnqueue() : gates.configEnqueue(), gates.get(), params, key, stats);
                bytesRead[0] += page.stat().resultBytes();
                List<PanLogPage.Entry> raw = page.entries();
                // A "geq <cursor>" answer holds nothing older than the cursor. If it does, the query was not honoured and
                // the page cannot be placed: UNKNOWN, cursor unchanged (dir=forward and query are vendor semantics not
                // yet cited, section 11).
                for (PanLogPage.Entry e : raw)
                    if (e.receiveTime().compareTo(cursorTime) < 0) throw new Unknown("QUERY_NOT_HONORED");
                String newest = raw.stream().map(PanLogPage.Entry::receiveTime).max(String::compareTo).orElse(cursorTime);
                final String boundaryTime = cursorTime;
                final TreeSet<String> boundarySeen = boundary;
                for (PanLogPage.Entry e : raw)
                    if (!(e.receiveTime().equals(boundaryTime) && boundarySeen.contains(e.seqno()))) fresh.add(e);
                TreeSet<String> atNewest = new TreeSet<>();
                for (PanLogPage.Entry e : raw) if (e.receiveTime().equals(newest)) atNewest.add(e.seqno());
                if (newest.equals(cursorTime)) atNewest.addAll(boundary);
                boolean full = raw.size() >= PAGE_SIZE;
                if (full && newest.equals(cursorTime)) {
                    // More than a page in one second: paging cannot advance. Say so and step over the second.
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("page_entries", raw.size());
                    detail.put("seconds_skipped", 1);
                    gaps.add(new Gap("PAGE_STALL", 0, 0, true, null, null, detail(detail)));
                    cursorTime = plusOneSecond(cursorTime);
                    boundary = new TreeSet<>();
                    newCursor = Optional.of(new PanCursor(cursorTime, List.of()));
                    break;
                }
                if (!newest.equals(cursorTime) || !atNewest.equals(boundary)) {
                    cursorTime = newest;
                    boundary = atNewest;
                    newCursor = Optional.of(new PanCursor(cursorTime, new ArrayList<>(boundary)));
                }
                if (!full) break;
                if (pageNo == MAX_PAGES) backlog = true;
            }
            if (backlog) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("pages", MAX_PAGES);
                detail.put("page_entries", PAGE_SIZE);
                gaps.add(new Gap("BACKLOG", 0, 0, true, null, null, detail(detail)));
            }
        }

        fresh.sort(Comparator.comparing(PanLogPage.Entry::receiveTime).thenComparing(PanLogPage.Entry::seqno, SEQNO_ORDER));
        seqnoJumps(fresh, previousSeqno).ifPresent(gaps::add);

        Optional<SystemLogCollectionRepository.Chunk> chunk = Optional.empty();
        int redactions = 0;
        long storedBytes = 0, ciphertextBytes = 0;
        Map<String, Integer> redactionsByRule = Map.of();
        if (!fresh.isEmpty()) {
            Stored written = store(run, source, fresh, retentionDays, hostnameSource);
            chunk = Optional.of(written.chunk());
            redactions = written.chunk().redactionCount();
            redactionsByRule = written.redactionsByRule();
            storedBytes = written.chunk().bytes();
            ciphertextBytes = written.chunk().manifest().ciphertextBytes();
        }

        String outcome = backlog ? "BACKLOG" : !gaps.isEmpty() ? "GAP" : !fresh.isEmpty() ? "COLLECTED" : "NO_NEW_DATA";
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("seed", seed);
        detail.put("pages", stats.stream().map(PageStat::toMap).toList());
        detail.put("new_entries", fresh.size());
        detail.put("redactions", redactions);
        if (chunk.isPresent()) {
            // system_log_chunk has no column or detail for it (V160), so the rule-set id and the per-rule counts of the
            // chunk are kept in this run's detail, next to the redaction total.
            detail.put("redaction_rule_set", PanLogRedactor.RULE_SET_ID);
            detail.put("redaction_by_rule", redactionsByRule);
        }
        detail.put("ciphertext_bytes", ciphertextBytes);
        return new SourceResult(new SystemLogCollectionRepository.SourceWrite(new SystemLogCollectionRepository.Run(outcome,
                bytesRead[0], storedBytes, run.reads, elapsed(started), detail(detail)), chunk, newCursor, gaps), outcome, outcome);
    }

    private record Stored(SystemLogCollectionRepository.Chunk chunk, Map<String, Integer> redactionsByRule) {
    }

    /** Redacts, serializes as JSON Lines, and writes one compressed, encrypted system_log artefact. */
    private Stored store(Run run, String source, List<PanLogPage.Entry> entries, int retentionDays, String hostnameSource) {
        int redactions = 0;
        Map<String, Integer> byRule = new java.util.TreeMap<>();
        java.io.ByteArrayOutputStream plain = new java.io.ByteArrayOutputStream();
        try {
            for (PanLogPage.Entry entry : entries) {
                PanLogRedactor.Result redacted = PanLogRedactor.redact(source, entry.fields());
                redactions += redacted.redactions();
                redacted.countsByRule().forEach((rule, n) -> byRule.merge(rule, n, Integer::sum));
                plain.write(json.writeValueAsBytes(redacted.fields()));
                plain.write('\n');
            }
        } catch (IOException serializationFailure) {
            throw new Unknown("CHUNK_NOT_SERIALIZABLE");
        }
        ArtefactStore.ArtefactMetadata meta;
        try (ArtefactStore.ArtefactHandle handle = artefacts.open(run.deviceId, run.jobId, VENDOR, true)) {
            OutputStream sink = handle.sink();
            sink.write(plain.toByteArray());
            meta = handle.finish();
        } catch (IOException | RuntimeException writeFailure) {
            throw new Unknown("ARTEFACT_WRITE_FAILED");
        }
        String artefactId = UUID.randomUUID().toString();
        var now = clock.instant();
        var manifest = new BackupArtefactManifestRecord(artefactId, run.deviceId, Optional.empty(), ArtefactClass.SYSTEM_LOG,
                VENDOR, Optional.empty(), fingerprint.of(hostnameSource), meta.plaintextSha256(), meta.plaintextBytes(),
                meta.ciphertextSha256(), meta.ciphertextBytes(), meta.keyId(), meta.wrappedDataKey(),
                ArtefactValidation.reachedWithoutRestore(ArtefactValidation.V1), "standard",
                Optional.of(now.plus(Duration.ofDays(retentionDays))), meta.ref().value(), Optional.empty());
        String first = entries.get(0).receiveTime(), last = entries.get(entries.size() - 1).receiveTime();
        var chunk = new SystemLogCollectionRepository.Chunk(UUID.randomUUID().toString(),
                now.atZone(JobWindowPolicy.ZONE_ID).toLocalDate(), fingerprint.of(source), 0, meta.plaintextBytes(),
                meta.plaintextBytes(), entries.size(), sqlTime(first), sqlTime(last), redactions, List.of(),
                fingerprint.ofContent(SystemLogCollectionRepository.CHUNK_CONTENT_DOMAIN, plain.toByteArray(), 0, plain.size()),
                manifest);
        return new Stored(chunk, Map.copyOf(byRule));
    }

    /** One page: enqueue, bounded poll to FIN, parse. The numbers of the page are appended to {@code stats}. */
    private Page fetch(Run run, GateResolution.Known enqueueGate, GateResolution.Known getGate, Map<String, String> params,
            String key, List<PageStat> stats) throws InterruptedException {
        String enqueued = call(run, enqueueGate, params, key);
        long enqueuedAt = clock.millis();
        String logJob = PanLogMeasurementSummary.enqueuedJobId(enqueued).filter(id -> JOB_ID.matcher(id).matches())
                .orElseThrow(() -> new Unknown("LOG_JOB_NOT_ENQUEUED"));
        for (int poll = 1; poll <= MAX_POLLS; poll++) {
            pause.sleep(POLL_INTERVAL);
            Map<String, String> getParams = new LinkedHashMap<>();
            getParams.put("action", "get");
            getParams.put("job-id", logJob);
            String answer = call(run, getGate, getParams, key);
            String status = PanLogMeasurementSummary.jobStatus(answer).orElseThrow(() -> new Unknown("LOG_JOB_STATUS_UNAVAILABLE"));
            if ("FIN".equals(status)) {
                long latency = Math.max(0, clock.millis() - enqueuedAt);
                PanLogPage page;
                try {
                    page = PanLogPage.parse(answer);
                } catch (PanLogPage.Unreadable unreadable) {
                    throw new Unknown(unreadable.code);
                }
                PageStat stat = new PageStat(page.entries().size(), answer.getBytes(StandardCharsets.UTF_8).length, latency, poll);
                stats.add(stat);
                return new Page(page.entries(), stat);
            }
        }
        throw new Unknown("LOG_JOB_NOT_FINISHED");
    }

    /** One gated, ledgered read; an answer that is not an HTTP 200 success within the size bound is UNKNOWN. */
    private String call(Run run, GateResolution.Known gate, Map<String, String> params, String key) {
        int step = run.step++;
        if (!attempts.findByJobAndStep(run.jobId, step).isEmpty()) throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
        String attempt = attempts.insertPreContact(run.jobId, run.epoch, step, gate.gateId(), "read", 1);
        if (attempt == null || !attempts.markBoundaryCrossed(attempt, run.epoch))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "PRE_CONTACT_UNCERTAIN");
        run.reads++;
        XmlApiResult result = transport.xmlApiCall(run.target, new XmlApiSpec("POST", "log", "", "direct_firewall",
                params, Map.of("X-PAN-KEY", key)), Duration.ofSeconds(gate.timeoutS()));
        String failure = !(result instanceof XmlApiResult.Completed completed) ? "TRANSPORT_FAILED"
                : completed.httpStatus() != 200 || completed.body() == null ? "API_ERROR"
                : completed.body().length() > PanSystemLogCollect.MAX_RESULT_CHARS ? "RESULT_TOO_LARGE"
                : !PanLogMeasurementSummary.success(completed.body()) ? "API_ERROR" : null;
        long bytes = result instanceof XmlApiResult.Completed completed && completed.body() != null
                ? completed.body().getBytes(StandardCharsets.UTF_8).length : 0L;
        if (!attempts.writeOutcome(attempt, run.epoch, failure == null ? "MATCHED" : "EXPECTATION_UNMET", failure,
                failure == null, bytes, null, null))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "ATTEMPT_RECORD_FAILED");
        if (failure != null) throw new Unknown(failure);
        return ((XmlApiResult.Completed) result).body();
    }

    /**
     * Section 6 "Loss detection": whether seqno is contiguous is UNKNOWN, so a jump is only ever
     * {@code SEQ_GAP_SUSPECTED} with its size, never a confirmed loss. One aggregated row per source per run.
     */
    private Optional<Gap> seqnoJumps(List<PanLogPage.Entry> sorted, String previousSeqno) {
        Long previous = previousSeqno == null ? null : seq(previousSeqno);
        long jumps = 0, missing = 0, largest = 0;
        for (PanLogPage.Entry e : sorted) {
            Long parsed = seq(e.seqno());
            if (parsed == null) continue;
            long seq = parsed;
            if (previous != null && seq > previous + 1) {
                long jump = seq - previous - 1;
                jumps++; missing += jump; largest = Math.max(largest, jump);
            }
            previous = previous == null ? seq : Math.max(previous, seq);
        }
        if (jumps == 0) return Optional.empty();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("jumps", jumps);
        detail.put("missing_seqnos", missing);
        detail.put("largest_jump", largest);
        return Optional.of(new Gap("SEQ_GAP_SUSPECTED", 0, 0, true, null, null, detail(detail)));
    }

    /** The newest receive_time of the entries and the seqnos stored at exactly that second. */
    private static PanCursor cursorAt(List<PanLogPage.Entry> entries) {
        String newest = entries.stream().map(PanLogPage.Entry::receiveTime).max(String::compareTo).orElseThrow();
        TreeSet<String> seqnos = new TreeSet<>();
        for (PanLogPage.Entry e : entries) if (e.receiveTime().equals(newest)) seqnos.add(e.seqno());
        return new PanCursor(newest, new ArrayList<>(seqnos));
    }

    /** Numeric order of digit strings of up to 20 characters (longer than a long is possible). */
    private static final Comparator<String> SEQNO_ORDER = Comparator.comparing(java.math.BigInteger::new);

    private static Long seq(String seqno) {
        try {
            return Long.parseLong(seqno);
        } catch (NumberFormatException tooLong) {
            return null;
        }
    }

    private static String plusOneSecond(String receiveTime) {
        return LocalDateTime.parse(receiveTime, PAN_TIME).plusSeconds(1).format(PAN_TIME);
    }

    private static String sqlTime(String receiveTime) {
        return LocalDateTime.parse(receiveTime, PAN_TIME).format(SQL_TIME);
    }

    private long elapsed(long started) {
        return Math.max(0, clock.millis() - started);
    }

    private String detail(Map<String, Object> detail) {
        try {
            return json.writeValueAsString(detail);
        } catch (IOException impossible) {
            return "{}";
        }
    }

    /** The existing PAN authentication path (as inventory and failover use it); the key lives in memory only. */
    private Optional<String> apiKey(ApiTarget target, String credentialRef) {
        try {
            var credential = credentials.resolve(credentialRef);
            var result = transport.xmlApiCall(target, new XmlApiSpec("POST", "keygen", "", "",
                    Map.of("user", credential.username(), "password", new String(credential.password())), Map.of()),
                    Duration.ofSeconds(PanSystemLogCollect.TIMEOUT_S));
            String body = result instanceof XmlApiResult.Completed c && c.httpStatus() == 200 ? c.body() : "";
            var match = KEY.matcher(body == null ? "" : body);
            return PanLogMeasurementSummary.success(body) && match.find() ? Optional.of(match.group(1)) : Optional.empty();
        } catch (RuntimeException unavailable) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(unavailable);
            if (unavailable instanceof com.securityexpert.nexus.ui2.worker.JobCancellationScope.Cancelled cancelled) throw cancelled;
            return Optional.empty();
        }
    }

    private void finish(String jobId, long epoch, JobState state, String reason) {
        leases.transitionState(jobId, epoch, JobState.EXECUTING, state, ACTOR, "syslog_collect_finished", reason);
    }
}
