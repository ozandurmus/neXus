package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSyslogRangedRead;
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSystemLogCollect;
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSystemLogCollect.Exec;
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSystemLogCollect.FileKind;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchStatus;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactValidation;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.diagnostic.CheckPointSystemLogRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.CheckPointSystemLogRepository.ChunkWrite;
import com.securityexpert.nexus.ui2.persistence.diagnostic.CheckPointSystemLogRepository.CpChunk;
import com.securityexpert.nexus.ui2.persistence.diagnostic.CheckPointSystemLogRepository.CpCursor;
import com.securityexpert.nexus.ui2.persistence.diagnostic.CheckPointSystemLogRepository.CrashAlert;
import com.securityexpert.nexus.ui2.persistence.diagnostic.CheckPointSystemLogRepository.CrashEntry;
import com.securityexpert.nexus.ui2.persistence.diagnostic.CheckPointSystemLogRepository.FileRow;
import com.securityexpert.nexus.ui2.persistence.diagnostic.CheckPointSystemLogRepository.NewCrashEntry;
import com.securityexpert.nexus.ui2.persistence.diagnostic.CheckPointSystemLogRepository.SnapshotEntry;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.worker.systemlog.AnchorHasher;
import com.securityexpert.nexus.ui2.worker.systemlog.DevicePlan;
import com.securityexpert.nexus.ui2.worker.systemlog.FileStat;
import com.securityexpert.nexus.ui2.worker.systemlog.Fingerprint;
import com.securityexpert.nexus.ui2.worker.systemlog.Gap;
import com.securityexpert.nexus.ui2.worker.systemlog.GapKind;
import com.securityexpert.nexus.ui2.worker.systemlog.LineBoundary;
import com.securityexpert.nexus.ui2.worker.systemlog.LineTimes;
import com.securityexpert.nexus.ui2.worker.systemlog.PlanContext;
import com.securityexpert.nexus.ui2.worker.systemlog.PlannerConfig;
import com.securityexpert.nexus.ui2.worker.systemlog.ReadAction;
import com.securityexpert.nexus.ui2.worker.systemlog.ReadPlanner;
import com.securityexpert.nexus.ui2.worker.systemlog.ReadPurpose;
import com.securityexpert.nexus.ui2.worker.systemlog.SourceCursor;
import com.securityexpert.nexus.ui2.worker.systemlog.SourceFamily;
import com.securityexpert.nexus.ui2.worker.systemlog.SourceOutcome;
import com.securityexpert.nexus.ui2.worker.systemlog.SourcePlan;
import com.securityexpert.nexus.ui2.worker.systemlog.StatParseResult;
import com.securityexpert.nexus.ui2.worker.systemlog.StatParser;
import com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver;

/**
 * System-log programme phase B, Check Point half (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md sections 3.2, 4, 5, 7, 8, 9,
 * 12; PO approval 2026-10-17): the {@code cp_system_log_collect} job on one Check Point Gaia gateway (VS0 only: the
 * login shell's own context; never {@code vsenv}). One trusted SSH session, every command strictly one after the other,
 * in the section 3.2 order:
 *
 * <ol>
 *   <li><b>C1</b> the family stat, parsed by shape ({@link StatParser}).</li>
 *   <li>The planner ({@link ReadPlanner#planDevice} with {@link PlannerConfig#defaults()}), then for each incremental
 *       source in run order its <b>C2</b> ranged reads through {@link CpSyslogRangedRead} only. Each read is checked
 *       (anchor fingerprint; on a mismatch the source is re-planned with {@link PlanContext#withMismatch}), trimmed to its
 *       last newline ({@link LineBoundary}), redacted ({@link ChunkRedactor}), written as one compressed, encrypted
 *       {@code system_log} artefact and recorded with the cursor it advances in one fenced transaction, before the next
 *       read is sent. Gaps carry byte counts; a budget leaves {@code BACKLOG} with the exact pending bytes.</li>
 *   <li><b>C3</b> the crash/dump listing: a baseline on the first listing, then new files raise one
 *       {@code device_crash_alert} per process. Content is never read.</li>
 *   <li>06:00 window only: <b>C6</b> + <b>C8</b> yesterday's sar text, <b>C7</b> + <b>C8</b> the newest settled HCP
 *       archive (stored as received, 7 days). A sar left {@code BACKLOG} by the device budget is retried in the next
 *       window of the same day.</li>
 * </ol>
 *
 * Spike Detective listings (C4, C5) and spike data fetches are not issued: {@code spike_data_enabled} is off by default
 * and its baseline storage is not built (TODO, section 8.3); when the setting is on, {@code cp_spike_data} is recorded
 * {@code UNSUPPORTED} with reason {@code SPIKE_DATA_NOT_IMPLEMENTED}.
 *
 * <p>Management servers are refused ({@code SYSTEM_LOG_DISABLED_ON_MANAGEMENT}: no MDS gate rows exist, section 2.3);
 * a Gaia Embedded device records {@code UNSUPPORTED} for every source without any device contact. Outcomes are exactly
 * the section 9 set. Device text never reaches a log line, a reason code or an exception message; file names are
 * stored only as HMACs.</p>
 */
public final class CpSystemLogCollectJobExecutor {
    static final String ACTOR = "system:worker";
    private static final String VENDOR = "check_point";
    static final String SOURCE_CRASH = "cp_crash_listing";
    /** HMAC domain of the section 4.1 anchors. */
    static final String ANCHOR_DOMAIN = "cp-syslog-anchor";
    static final String SOURCE_SPIKE_DATA = "cp_spike_data";
    static final String CRASH_DIR = "/var/log/crash";
    static final String DUMP_DIR = "/var/log/dump/usermode";
    /** Section 8.1: an HCP archive is fetched only when its mtime is at least this much older than the device clock. */
    static final long SETTLE_SECONDS = 600;
    /** Section 8.2: the sar candidate's mtime falls in the 30 h before the device clock. */
    static final long SAR_WINDOW_SECONDS = 30L * 3600;
    /** Re-plans of one source after anchor mismatches (each adds one path; a family has few members). */
    static final int MAX_REPLANS = 16;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern SUFFIX = Pattern.compile("[A-Za-z0-9.]{0,16}");
    /** Section 8.1, not verified against real names (masked in the measurement): flagged, never refused. */
    private static final Pattern HCP_STRICT = Pattern.compile(
            "hcp_report_[A-Za-z0-9._-]+_([0-9]{2})_([0-9]{2})_([0-9]{2})_([0-9]{2})_([0-9]{2})\\.tar\\.gz");
    /** Section 7: the process-name parse. Only a {@code <daemon>.<pid>.core[.gz]} name of a known daemon is named. */
    private static final Pattern CORE_NAME = Pattern.compile("([A-Za-z0-9_+-]{1,64})\\.[0-9]{1,10}\\.core(?:\\.gz)?");
    private static final Set<String> KNOWN_PROCESSES = Set.of(
            "fwd", "fw", "fwk", "cpd", "cpwd", "cpm", "cprid", "cpviewd", "cpca", "cpmad", "cpsead", "cpstat",
            "vpnd", "mpdaemon", "rad", "pdpd", "pepd", "dlpda", "sfwd", "routed", "clish", "sshd", "httpd",
            "httpd2", "nginx", "java", "snmpd", "syslogd", "fwm", "fwssd", "cphamcset", "dleserver", "log_indexer",
            "dynamic_objects", "cpdnsd", "dnsmasq", "crond", "lighttpd", "xinetd", "gated", "ospfd", "bgpd", "ripd", "zebra");
    private static final Set<String> CLEAN_OUTCOMES = Set.of("COLLECTED", "NO_NEW_DATA", "BACKLOG", "GAP", "ABSENT",
            "UNSUPPORTED", "TOO_LARGE");

    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DeviceRepository devices;
    private final DeviceTransport ssh;
    private final GateRegistryPort gates;
    private final CheckPointSystemLogRepository repository;
    private final ArtefactStore artefacts;
    private final SystemLogArtefactVolume volume;
    private final HostnameFingerprint fingerprint;
    private final DevicePlatformFactsRepository platformFacts;
    private final ChunkRedactor redactor;
    private final Clock clock;
    private final PlannerConfig config;
    private final AnchorHasher anchors;
    private final ObjectMapper json = new ObjectMapper();

    public CpSystemLogCollectJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceTransport ssh, GateRegistryPort gates, CheckPointSystemLogRepository repository, ArtefactStore artefacts,
            SystemLogArtefactVolume volume, HostnameFingerprint fingerprint, DevicePlatformFactsRepository platformFacts,
            ChunkRedactor redactor) {
        this(leases, attempts, devices, ssh, gates, repository, artefacts, volume, fingerprint, platformFacts, redactor,
                Clock.systemUTC(), PlannerConfig.defaults());
    }

    CpSystemLogCollectJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceTransport ssh, GateRegistryPort gates, CheckPointSystemLogRepository repository, ArtefactStore artefacts,
            SystemLogArtefactVolume volume, HostnameFingerprint fingerprint, DevicePlatformFactsRepository platformFacts,
            ChunkRedactor redactor, Clock clock, PlannerConfig config) {
        this.leases = leases; this.attempts = attempts; this.devices = devices; this.ssh = ssh; this.gates = gates;
        this.repository = repository; this.artefacts = artefacts; this.volume = volume; this.fingerprint = fingerprint;
        this.platformFacts = platformFacts == null ? DevicePlatformFactsRepository.NONE : platformFacts;
        this.redactor = redactor == null ? ChunkRedactor.NOT_WIRED : redactor;
        this.clock = clock; this.config = config;
        // Section 4.1: HMAC-SHA256 under the keyed-fingerprint secret (UI2_HOSTNAME_FINGERPRINT_KEY_FILE), the one key of
        // every content-derived identifier of this collector: anchors, chunk and file content identities, file names.
        // Each use has its own domain.
        this.anchors = bytes -> fingerprint.ofContent(ANCHOR_DOMAIN, bytes, 0, bytes.length);
    }

    /** A step the job could not record or a lease it lost: the job stops; nothing further is sent. */
    private static final class Abort extends RuntimeException {
        final JobState state; final String code;
        Abort(JobState state, String code) { super(code, null, false, false); this.state = state; this.code = code; }
    }

    /** One read's typed failure: the source (or listing) is recorded UNKNOWN; the next one still runs. */
    private static final class Unknown extends RuntimeException {
        final String code;
        Unknown(String code) { super(code, null, false, false); this.code = code; }
    }

    private record Gates(GateResolution.Known stat, GateResolution.Known crash, GateResolution.Known sa,
            GateResolution.Known hcp, GateResolution.Known fetch) {
        static Optional<Gates> resolve(GateRegistryPort registry) {
            var a = CpSystemLogCollect.gate(Exec.STAT, registry);
            var b = CpSystemLogCollect.gate(Exec.LS_CRASH, registry);
            var c = CpSystemLogCollect.gate(Exec.LS_SA, registry);
            var d = CpSystemLogCollect.gate(Exec.LS_HCP, registry);
            var e = CpSystemLogCollect.fetchGate(registry);
            return a.isPresent() && b.isPresent() && c.isPresent() && d.isPresent() && e.isPresent()
                    ? Optional.of(new Gates(a.get(), b.get(), c.get(), d.get(), e.get())) : Optional.empty();
        }
    }

    private static final class Run {
        final String jobId; final long epoch; final String deviceId; final TransportSession session;
        final SystemLogCollectionRepository.Settings settings; final String softwareVersion; final String hostnameSource;
        int step; long deviceSpent; String statFailure;
        final List<String> failures = new ArrayList<>();
        Run(String jobId, long epoch, String deviceId, TransportSession session, SystemLogCollectionRepository.Settings settings,
                String softwareVersion, String hostnameSource) {
            this.jobId = jobId; this.epoch = epoch; this.deviceId = deviceId; this.session = session; this.settings = settings;
            this.softwareVersion = softwareVersion; this.hostnameSource = hostnameSource;
        }
        void outcome(String outcome, String reason) {
            if (!CLEAN_OUTCOMES.contains(outcome)) failures.add(reason == null ? outcome : reason);
        }
    }

    public void execute(String jobId, long epoch, String deviceId, String host, int port, String credentialRef) {
        Optional<DeviceRecord> device = devices.find(deviceId);
        var endpoint = devices.findEndpointByDeviceId(deviceId)
                .filter(e -> deviceId.equals(e.deviceId()) && "ssh_exec".equals(e.transportKind()));
        // Section 2.3: no management-server gate rows exist, so a management server is refused whatever the setting.
        if (device.isPresent() && VENDOR.equals(device.get().vendorHint()) && "management_server".equals(device.get().role())) {
            leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.REJECTED, ACTOR, "syslog_collect_claim_check",
                    "SYSTEM_LOG_DISABLED_ON_MANAGEMENT");
            return;
        }
        Optional<Gates> resolved = Gates.resolve(gates);
        if (device.isEmpty() || !device.get().permitsReadCollection() || !VENDOR.equals(device.get().vendorHint())
                || !"gateway".equals(device.get().role()) || endpoint.isEmpty() || repository == null || artefacts == null
                || volume == null || resolved.isEmpty() || host == null || credentialRef == null) {
            leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.REJECTED, ACTOR, "syslog_collect_claim_check",
                    "SYSTEM_LOG_COLLECT_UNAVAILABLE");
            return;
        }
        if (!leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR, "syslog_collect_start")) return;
        TransportSession session = null;
        try {
            if (!attempts.findByJobAndStep(jobId, 0).isEmpty()) throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
            var summary = devices.findSummary(deviceId);
            var settings = repository.settings();
            String version = summary.flatMap(DeviceSummaryRecord::observedSoftwareVersion).orElse("unknown");
            String hostnameSource = summary.flatMap(DeviceSummaryRecord::observedHostname).orElse(deviceId);
            if (gaiaEmbedded(deviceId, summary)) {
                Run unsupported = new Run(jobId, epoch, deviceId, null, settings, version, hostnameSource);
                for (SourceFamily family : SourceFamily.runOrder())
                    recordSource(unsupported, family.sourceKey(), runRow("UNSUPPORTED", "GAIA_EMBEDDED", Map.of()), Optional.empty(), List.of());
                recordCrash(unsupported, runRow("UNSUPPORTED", "GAIA_EMBEDDED", Map.of()),
                        new CheckPointSystemLogRepository.CrashListingWrite(List.of(), List.of(), List.of(), List.of()));
                finish(jobId, epoch, JobState.COMPLETED, "SYSTEM_LOG_UNSUPPORTED_PLATFORM");
                return;
            }
            var connected = ssh.connect(new ConnectionTarget(UUID.randomUUID().toString(), host, port),
                    new ConnectSpec(credentialRef, PersistedManagementEndpointTrustResolver.scopeRef(host, port), Optional.empty()),
                    Duration.ofSeconds(30));
            if (!(connected instanceof ConnectResult.Authenticated authenticated)) {
                Run failed = new Run(jobId, epoch, deviceId, null, settings, version, hostnameSource);
                for (SourceFamily family : SourceFamily.runOrder())
                    recordSource(failed, family.sourceKey(), runRow("UNKNOWN", "CONNECTION_FAILED", Map.of()), Optional.empty(), List.of());
                recordCrash(failed, runRow("UNKNOWN", "CONNECTION_FAILED", Map.of()),
                        new CheckPointSystemLogRepository.CrashListingWrite(List.of(), List.of(), List.of(), List.of()));
                finish(jobId, epoch, JobState.FAILED, "partial:CONNECTION_FAILED");
                return;
            }
            session = authenticated.session();
            Run run = new Run(jobId, epoch, deviceId, session, settings, version, hostnameSource);
            StatParseResult stat = stat(run, resolved.get());
            incremental(run, stat);
            crashListing(run, resolved.get());
            dailyItems(run, resolved.get(), stat);
            if (settings.spikeDataEnabled()) {
                // TODO(phase B §8.3): C4/C5 listings, the directory baseline and the spike data fetch. Not built: the
                // data-directory layout is unmeasured and no baseline table exists. Recorded, never silently skipped.
                recordSource(run, SOURCE_SPIKE_DATA, runRow("UNSUPPORTED", "SPIKE_DATA_NOT_IMPLEMENTED", Map.of()),
                        Optional.empty(), List.of());
            }
            finish(jobId, epoch, run.failures.isEmpty() ? JobState.COMPLETED : JobState.FAILED,
                    run.failures.isEmpty() ? "SYSTEM_LOG_COLLECTED" : "partial:" + run.failures.get(0));
        } catch (Abort abort) {
            finish(jobId, epoch, abort.state, abort.code);
        } catch (Exception failure) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(failure);
            finish(jobId, epoch, JobState.OUTCOME_UNKNOWN, "SYSLOG_COLLECT_OUTCOME_UNCERTAIN");
        } finally {
            if (session != null) ssh.disconnect(session);
        }
    }

    private boolean gaiaEmbedded(String deviceId, Optional<DeviceSummaryRecord> summary) {
        boolean family = platformFacts.find(deviceId).flatMap(f -> f.platformFamily()).filter("gaia_embedded"::equals).isPresent();
        return family || com.securityexpert.nexus.ui2.jobs.admission.CheckPointSparkModelHint.isKnownSparkModel(
                summary.flatMap(DeviceSummaryRecord::observedModel));
    }

    // ================================================================ C1

    /** C1; a failed or unreadable stat yields an unusable result, so every incremental source is UNKNOWN. */
    private StatParseResult stat(Run run, Gates g) {
        try {
            return StatParser.parse(exec(run, Exec.STAT, g.stat()));
        } catch (Unknown unknown) {
            run.statFailure = unknown.code;
            return StatParser.parse("");
        }
    }

    // ================================================================ C2: incremental sources

    private void incremental(Run run, StatParseResult stat) {
        Map<SourceFamily, SourceCursor> cursors = new java.util.EnumMap<>(SourceFamily.class);
        Map<SourceFamily, Optional<CpCursor>> stored = new java.util.EnumMap<>(SourceFamily.class);
        Set<SourceFamily> invalid = java.util.EnumSet.noneOf(SourceFamily.class);
        for (SourceFamily family : SourceFamily.runOrder()) {
            Optional<CpCursor> cursor = repository.findCpCursor(run.deviceId, family.sourceKey());
            stored.put(family, cursor);
            if (cursor.isPresent()) {
                SourceCursor planned = toPlanner(cursor.get());
                if (planned == null) invalid.add(family);
                else cursors.put(family, planned);
            }
        }
        DevicePlan devicePlan = ReadPlanner.planDevice(cursors, stat, config);
        long plannedBefore = 0;
        for (SourceFamily family : SourceFamily.runOrder()) {
            SourcePlan initial = devicePlan.of(family);
            SourcePlan plan = run.deviceSpent == plannedBefore ? initial
                    : ReadPlanner.plan(family, cursors.get(family), stat, config, new PlanContext(run.deviceSpent, Set.of()));
            plannedBefore += initial.plannedBytes();
            SourceResult result;
            try {
                result = invalid.contains(family) ? SourceResult.unknown("CURSOR_UNREADABLE")
                        : collectSource(run, family, cursors.get(family), stored.get(family).orElse(null), stat, plan);
            } catch (Unknown unknown) {
                result = SourceResult.unknown(unknown.code);
            }
            run.outcome(result.outcome, result.reason);
            recordSource(run, family.sourceKey(), runRow(result.outcome, result.reason, result.detail, result.durationMs), result.cursor,
                    result.gaps);
        }
    }

    /** What one source leaves for its final, fenced write. */
    private static final class SourceResult {
        String outcome; String reason; Optional<CpCursor> cursor = Optional.empty();
        long durationMs;
        List<SystemLogCollectionRepository.Gap> gaps = new ArrayList<>(); Map<String, Object> detail = new LinkedHashMap<>();
        static SourceResult of(String outcome, String reason) {
            SourceResult r = new SourceResult(); r.outcome = outcome; r.reason = reason; return r;
        }
        static SourceResult unknown(String reason) { return of("UNKNOWN", reason); }
    }

    /** The resume position of a source while it is being read: inode text, offset, anchor; dirty = not yet stored. */
    private record Position(String inode, long offset, String anchor) {
    }

    private SourceResult collectSource(Run run, SourceFamily family, SourceCursor cursor, CpCursor storedCursor,
            StatParseResult stat, SourcePlan firstPlan) {
        if (firstPlan.outcome() == SourceOutcome.UNKNOWN) {
            String reason = firstPlan.unknownReason() == null ? "STAT_UNREADABLE" : firstPlan.unknownReason().name();
            if (run.statFailure != null && "STAT_UNREADABLE".equals(reason)) reason = "STAT_" + run.statFailure;
            return SourceResult.unknown(reason);
        }
        if (firstPlan.outcome() == SourceOutcome.ABSENT) return SourceResult.of("ABSENT", null);
        long epochNow = stat.deviceEpoch();
        List<SnapshotEntry> snapshot = snapshot(firstPlan.familySnapshot());
        FileStat base = baseOf(stat, family);
        if (firstPlan.reads().isEmpty() && firstPlan.seedCursorInode() != null) {
            SourceResult seeded = SourceResult.of("NO_NEW_DATA", null);
            seeded.cursor = Optional.of(new CpCursor(Long.toString(firstPlan.seedCursorInode()), 0, null, snapshot, epochNow));
            seeded.detail.put("seeded_empty_file", true);
            return seeded;
        }
        if (firstPlan.reads().isEmpty() && firstPlan.outcome() == SourceOutcome.NO_NEW_DATA) {
            SourceResult none = SourceResult.of("NO_NEW_DATA", null);
            // Position unchanged; the snapshot and device epoch are refreshed (the gap window starts at the last read).
            if (storedCursor != null) none.cursor = Optional.of(new CpCursor(storedCursor.inode(), storedCursor.offsetBytes(),
                    storedCursor.anchorHmac(), snapshot, epochNow));
            return none;
        }
        if (!firstPlan.reads().isEmpty() && redactor == ChunkRedactor.NOT_WIRED) return SourceResult.unknown("REDACTOR_MISSING");
        if (!firstPlan.reads().isEmpty() && volume.freeFraction() < SystemLogArtefactVolume.MIN_FREE_FRACTION) {
            SourceResult refused = SourceResult.of("STORAGE_REFUSED", "ARTEFACT_VOLUME_LOW_SPACE");
            return refused;
        }

        Set<String> printed = new HashSet<>();
        for (FileStat f : stat.files()) printed.add(f.path());
        CpSyslogRangedRead.Family readFamily = readFamily(family);
        long spentBeforeSource = run.deviceSpent;
        Set<String> mismatches = new HashSet<>();
        SourcePlan plan = firstPlan;
        List<Gap> lossGaps = lossGaps(plan);
        boolean lossGapsWritten = false;
        Position pos = storedCursor == null ? null
                : new Position(storedCursor.inode(), storedCursor.offsetBytes(), storedCursor.anchorHmac());
        boolean dirty = false, touchedBase = false, noBaseline = false, lineSplitAny = false;
        // A file found smaller than a read's start (changed between this run's stat and the read). With a cursor the source is
        // deferred (see below); only a first run, which holds nothing yet, restarts the file from byte 0 inside this run.
        Set<String> shrinkRestarts = new HashSet<>();
        boolean shrinkVoidsBaseline = false;
        long shrinkPending = 0;
        long payloadRead = 0, discarded = 0, storedBytes = 0, carriedAtStop = 0;
        int reads = 0, chunks = 0, replans = 0, redactions = 0;
        String ruleSet = null;
        Map<String, Integer> byRule = new TreeMap<>();
        String failure = null, failureOutcome = "UNKNOWN";
        boolean transportFailure = false;
        // Elapsed time of this source's reads: first read sent to the last read answered or chunk stored. The stat share
        // (C1) is before the first read and the listing after the last, so neither is counted.
        long firstReadAt = -1, lastActivityAt = -1;
        List<ReadAction> actions = plan.reads();
        int i = 0;
        boolean repeat = false;
        readLoop:
        while (i < actions.size()) {
            ReadAction original = actions.get(i);
            ReadAction r = original;
            if (repeat) {
                // The previous read of this action was trimmed short of its planned end: continue the same range.
                repeat = false;
                long left = Math.max(0, original.endOffset() - pos.offset());
                long length = Math.min(config.readCapBytes(), left);
                r = new ReadAction(original.path(), original.inode(), pos.offset(), length,
                        (int) Math.min(config.anchorBytes(), pos.offset()), pos.anchor(), original.purpose(), true,
                        original.finalFile() && pos.offset() + length == original.endOffset());
            } else if (r.continuation()) {
                if (pos == null || !pos.inode().equals(Long.toString(r.inode()))) { failure = "CONTINUATION_WITHOUT_POSITION"; break; }
                r = r.rebase(pos.offset(), pos.anchor(), config);
            }
            if (r.rangeLength() <= 0) { i++; continue; }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream((int) Math.min(r.rangeLength(), 1 << 20));
            if (firstReadAt < 0) firstReadAt = clock.millis();
            RangedFetchResult result = rangedRead(run, readFamily, base, printed, r, buffer);
            lastActivityAt = clock.millis();
            reads++;
            if (result.status() == RangedFetchStatus.SIZE_BELOW_OFFSET && result.sizeAtOpen().isPresent()) {
                if (pos == null && r.purpose() == ReadPurpose.INITIAL_BASELINE && r.path().equals(base.path())
                        && shrinkRestarts.add(r.path())) {
                    // First run, nothing read before: nothing is lost. The whole (now smaller) file replaces the baseline,
                    // under the same budgets; the failed read's prefix is discarded and nothing was stored for it.
                    lossGaps = new ArrayList<>(lossGaps);
                    lossGaps.removeIf(g -> g.kind() == GapKind.INITIAL_BASELINE);
                    shrinkVoidsBaseline = true;
                    long sourceLeft = config.sourceBudgetBytes() - (run.deviceSpent - spentBeforeSource);
                    long deviceLeft = config.deviceBudgetBytes() - run.deviceSpent;
                    ReadPlanner.Restart restart = ReadPlanner.restartFromStart(r.path(), r.inode(),
                            result.sizeAtOpen().getAsLong(), Math.min(sourceLeft, deviceLeft), config);
                    shrinkPending += restart.pendingBytes();
                    List<ReadAction> rebuilt = new ArrayList<>(actions.subList(0, i));
                    rebuilt.addAll(restart.reads());
                    for (int j = i + 1; j < actions.size(); j++)
                        if (!actions.get(j).path().equals(r.path())) rebuilt.add(actions.get(j));
                    actions = rebuilt;
                    pos = new Position(Long.toString(r.inode()), 0, null);
                    dirty = true;
                    repeat = false;
                    continue readLoop;
                }
                // With a cursor the file changed under the plan (truncated, or renamed and replaced; SFTP v3 cannot tell
                // which). Not a collection failure and nothing to guess: the cursor, the anchor and the gaps stay as they
                // are, the source is deferred, and the next run's stat decides TRUNCATED or rotation (section 4.4).
                failure = "FILE_CHANGED_DURING_READ";
                failureOutcome = "BACKLOG";
                break;
            }
            if (!result.bytesUsable()) {
                failure = "READ_" + result.status().name();
                transportFailure = result.status() != RangedFetchStatus.REFUSED;
                break;
            }
            byte[] data = buffer.toByteArray();
            int received = (int) Math.min(data.length, result.bytesReceived());
            int leadIn = r.leadIn();
            if (received < leadIn) { failure = "ANCHOR_REGION_NOT_RECEIVED"; break; }
            int payload = received - leadIn;
            run.deviceSpent += payload;
            payloadRead += payload;
            if (r.verifiesAnchor()) {
                Fingerprint.Result check = Fingerprint.verify(anchors, data, received, leadIn, r.expectedAnchor());
                if (check == Fingerprint.Result.SHORT_READ) { failure = "ANCHOR_REGION_NOT_RECEIVED"; break; }
                if (check == Fingerprint.Result.MISMATCH) {
                    if (r.continuation() || chunks > 0 || dirty) { failure = "CONTENT_CHANGED_DURING_RUN"; break; }
                    if (++replans > MAX_REPLANS) { failure = "ANCHOR_REPLAN_LIMIT"; break; }
                    discarded += payload;
                    mismatches.add(r.path());
                    plan = ReadPlanner.plan(family, cursor, stat, config,
                            new PlanContext(run.deviceSpent, mismatches));
                    lossGaps = lossGaps(plan);
                    actions = plan.reads();
                    i = 0;
                    continue readLoop;
                }
            }
            boolean fullCap = payload >= config.readCapBytes();
            int skip = 0;
            boolean lineSplit = false;
            if (r.purpose() == ReadPurpose.INITIAL_BASELINE && !r.continuation()) {
                LineBoundary.Leading leading = LineBoundary.leading(data, leadIn, payload, fullCap);
                skip = leading.skippedBytes();
                lineSplit = leading.lineSplit();
                if (skip > 0 && skip < payload) lossGaps = addBaselineBytes(lossGaps, skip);
            }
            int stored;
            if (lineSplit) {
                stored = payload;
            } else {
                LineBoundary.Trim trim = LineBoundary.trailing(data, leadIn + skip, payload - skip, fullCap, r.finalFile());
                stored = trim.storedBytes();
                lineSplit = trim.lineSplit();
                carriedAtStop = trim.carriedBytes();
            }
            if (r.purpose() == ReadPurpose.INITIAL_BASELINE && pos == null && stored == 0) {
                // One partial line only: nothing storable yet, no cursor, no baseline gap; the next run starts again.
                noBaseline = true;
                break;
            }
            long newOffset = r.offset() + skip + stored;
            String anchor = Fingerprint.nextAnchor(anchors, data, leadIn + skip + stored, newOffset, config);
            Position next = new Position(Long.toString(r.inode()), newOffset, anchor);
            if (r.inode() == base.inode() && r.path().equals(base.path())) touchedBase = true;
            if (stored > 0) {
                if (volume.freeFraction() < SystemLogArtefactVolume.MIN_FREE_FRACTION) {
                    failure = "ARTEFACT_VOLUME_LOW_SPACE"; failureOutcome = "STORAGE_REFUSED"; break;
                }
                ChunkRedactor.Redaction redaction;
                try {
                    redaction = redactor.redact(family, data, leadIn + skip, stored);
                } catch (RuntimeException redactionFailure) {
                    failure = "REDACTION_FAILED"; break;
                }
                byte[] deviceBytes = java.util.Arrays.copyOfRange(data, leadIn + skip, leadIn + skip + stored);
                ArtefactStore.ArtefactMetadata meta = write(run, redaction.bytes(), true);
                List<String> flags = new ArrayList<>();
                if (lineSplit) flags.add("LINE_SPLIT");
                if (plan.rotationFollowed()) flags.add("ROTATION_FOLLOWED");
                var times = LineTimes.extract(family, deviceBytes, epochNow, config);
                var manifest = manifest(run, meta, run.settings.rawRetentionDays());
                var chunk = new SystemLogCollectionRepository.Chunk(UUID.randomUUID().toString(),
                        clock.instant().atZone(JobWindowPolicy.ZONE_ID).toLocalDate(), fileHmac(run.deviceId, r.path()),
                        r.offset() + skip, newOffset, stored, lines(deviceBytes), times.first().text(), times.last().text(),
                        redaction.redactionCount(), flags, fingerprint.ofContent(SystemLogCollectionRepository.CHUNK_CONTENT_DOMAIN,
                                redaction.bytes(), 0, redaction.bytes().length), manifest);
                CpCursor advanced = new CpCursor(next.inode(), next.offset(), next.anchor(), snapshot, epochNow);
                List<SystemLogCollectionRepository.Gap> withChunk = lossGapsWritten ? List.of() : toRows(lossGaps, run.deviceId);
                ChunkWrite written;
                try {
                    written = repository.recordChunk(run.jobId, run.epoch, run.deviceId, family.sourceKey(),
                            new CpChunk(chunk, next.inode(), redaction.ruleSetId()), advanced, withChunk);
                } catch (RuntimeException notRecorded) {
                    volume.discard(meta.ref());
                    throw notRecorded;
                }
                switch (written) {
                    case NOT_RECORDED -> { volume.discard(meta.ref()); throw new Abort(JobState.OUTCOME_UNKNOWN, "SYSTEM_LOG_NOT_RECORDED"); }
                    case IDENTITY_CONFLICT -> { volume.discard(meta.ref()); failure = "CHUNK_IDENTITY_CONFLICT"; break readLoop; }
                    case DUPLICATE -> volume.discard(meta.ref());
                    case STORED -> { chunks++; storedBytes += stored; }
                }
                lastActivityAt = clock.millis();
                lossGapsWritten = true;
                dirty = false;
                redactions += redaction.redactionCount();
                ruleSet = redaction.ruleSetId();
                redaction.countsByRule().forEach((rule, n) -> byRule.merge(rule, n, Integer::sum));
                lineSplitAny |= lineSplit;
            } else {
                dirty = true;
            }
            pos = next;
            if (stored > 0 && fullCap && newOffset < original.endOffset()) repeat = true;
            else i++;
        }

        SourceResult result;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reads", reads);
        detail.put("chunks", chunks);
        detail.put("payload_bytes", payloadRead);
        detail.put("discarded_bytes", discarded);
        detail.put("stored_bytes", storedBytes);
        detail.put("anchor_mismatches", mismatches.size());
        detail.put("redactions", redactions);
        if (ruleSet != null) detail.put("redaction_rule_set", ruleSet);
        if (!byRule.isEmpty()) detail.put("redactions_by_rule", byRule);
        detail.put("line_split", lineSplitAny);
        detail.put("rotation_followed", plan.rotationFollowed());
        detail.put("fast_rotation", plan.fastRotation());
        detail.put("rotations_since_last_read", plan.rotationsSinceLastRead());
        if (!shrinkRestarts.isEmpty()) detail.put("shrink_restarts", shrinkRestarts.size());
        if ("BACKLOG".equals(failureOutcome)) detail.put("pending_bytes_unknown", true);
        // The plan's pending bytes sat in the file that shrank when its BACKLOG row names only that file: they are gone.
        Gap planBacklog = plan.gap(GapKind.BACKLOG);
        long planPending = !shrinkRestarts.isEmpty() && planBacklog != null
                && planBacklog.affectedPaths().equals(List.of(base.path())) ? 0 : plan.pendingBytes();
        long pendingTotal = planPending + shrinkPending;
        if (failure != null) {
            result = "STORAGE_REFUSED".equals(failureOutcome) || "BACKLOG".equals(failureOutcome)
                    ? SourceResult.of(failureOutcome, failure) : SourceResult.unknown(failure);
            if (transportFailure) result.gaps.add(row(Gap.collectionFailed(cursor == null ? epochNow : cursor.deviceEpochAtRead(),
                    epochNow), run.deviceId));
        } else if (noBaseline) {
            result = SourceResult.of("NO_NEW_DATA", null);
            detail.put("partial_line_only", true);
        } else {
            boolean completed = pendingTotal == 0;
            if (completed && !touchedBase && base.size() == 0 && (pos == null || !pos.inode().equals(Long.toString(base.inode()))
                    || plan.gaps().stream().anyMatch(Gap::isLoss))) {
                // The chain ends in an empty live file (restart after a rotation or truncation): the cursor moves to it.
                pos = new Position(Long.toString(base.inode()), 0, null);
                dirty = true;
            }
            final boolean baselineVoided = shrinkVoidsBaseline;
            boolean loss = plan.gaps().stream()
                    .anyMatch(g -> g.isLoss() && !(baselineVoided && g.kind() == GapKind.INITIAL_BASELINE));
            String outcome = pendingTotal > 0 ? "BACKLOG" : loss ? "GAP" : chunks > 0 ? "COLLECTED" : "NO_NEW_DATA";
            result = SourceResult.of(outcome, null);
            if (!lossGapsWritten) result.gaps.addAll(toRows(lossGaps, run.deviceId));
            Gap backlog = pendingTotal > 0 ? (planBacklog != null ? planBacklog : Gap.backlog(0,
                    cursor == null ? epochNow : cursor.deviceEpochAtRead(), epochNow, List.of(base.path()))) : null;
            if (backlog != null) {
                long pending = pendingTotal + carriedAtStop;
                detail.put("pending_bytes", pending);
                result.gaps.add(row(new Gap(GapKind.BACKLOG, 0, false, pending, false, backlog.windowStartEpoch(),
                        backlog.windowEndEpoch(), backlog.affectedPaths(), 0, 0), run.deviceId));
            }
            if (pos != null) result.cursor = Optional.of(new CpCursor(pos.inode(), pos.offset(), pos.anchor(), snapshot, epochNow));
        }
        result.detail = detail;
        result.durationMs = firstReadAt < 0 ? 0 : Math.max(0, lastActivityAt - firstReadAt);
        return result;
    }

    /** One C2 read: gated and path-checked by {@link CpSyslogRangedRead}, ledgered like every device step. */
    private RangedFetchResult rangedRead(Run run, CpSyslogRangedRead.Family family, FileStat base, Set<String> printed,
            ReadAction r, OutputStream sink) {
        int step = run.step++;
        if (!attempts.findByJobAndStep(run.jobId, step).isEmpty()) throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
        String attempt = attempts.insertPreContact(run.jobId, run.epoch, step, CpSyslogRangedRead.GATE_ID, "read", 1);
        if (attempt == null || !attempts.markBoundaryCrossed(attempt, run.epoch))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "PRE_CONTACT_UNCERTAIN");
        RangedFetchResult result = CpSyslogRangedRead.read(ssh, run.session, gates, new CpSyslogRangedRead.ReadRequest(
                CpSystemLogCollect.PLATFORM_ROLE_SCOPE, family, base.path(), printed, r.path(), r.rangeStart(),
                r.rangeLength(), config.maxRangeBytes()), sink);
        boolean ok = result.bytesUsable();
        if (!attempts.writeOutcome(attempt, run.epoch, ok ? "MATCHED" : "EXPECTATION_UNMET",
                ok ? null : "RANGE_" + result.status().name(), ok, result.bytesReceived(), null, null))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "ATTEMPT_RECORD_FAILED");
        return result;
    }

    // ================================================================ C3: crash and dump listing

    private void crashListing(Run run, Gates g) {
        long started = clock.millis();
        String output;
        try {
            output = exec(run, Exec.LS_CRASH, g.crash());
        } catch (Unknown unknown) {
            run.outcome("UNKNOWN", unknown.code);
            recordCrash(run, runRow("UNKNOWN", unknown.code, Map.of()),
                    new CheckPointSystemLogRepository.CrashListingWrite(List.of(), List.of(), List.of(), List.of()));
            return;
        }
        CpLsListing.Result listing = CpLsListing.parse(output, null);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ignored_lines", listing.ignoredLines());
        detail.put("rejected_lines", listing.rejectedLines());
        boolean crashPresent = listing.sections().contains(CRASH_DIR), dumpPresent = listing.sections().contains(DUMP_DIR);
        detail.put("crash_directory_present", crashPresent);
        detail.put("dump_directory_present", dumpPresent);
        if (listing.sections().isEmpty()) {
            boolean unattributed = listing.rejectedLines() > 0;
            String outcome = unattributed ? "UNKNOWN" : "ABSENT";
            run.outcome(outcome, unattributed ? "LISTING_UNATTRIBUTED" : null);
            recordCrash(run, runRow(outcome, unattributed ? "LISTING_UNATTRIBUTED" : null, detail),
                    new CheckPointSystemLogRepository.CrashListingWrite(List.of(), List.of(), List.of(), List.of()));
            return;
        }
        boolean baseline = !repository.crashBaselineTaken(run.deviceId);
        Map<String, CrashEntry> live = new LinkedHashMap<>();
        for (CrashEntry e : repository.liveCrashEntries(run.deviceId)) live.put(e.directory() + "|" + e.nameHmac() + "|" + e.mtime(), e);
        Set<String> matched = new HashSet<>();
        List<NewCrashEntry> inserted = new ArrayList<>();
        List<CrashEntry> seen = new ArrayList<>();
        Map<String, long[]> byProcess = new TreeMap<>();
        int files = 0;
        for (CpLsListing.Entry e : listing.entries()) {
            String directory = CRASH_DIR.equals(e.section()) ? "crash" : DUMP_DIR.equals(e.section()) ? "dump_usermode" : null;
            if (directory == null) continue;
            files++;
            String hmac = fingerprint.of("cp-crash|" + directory + "|" + e.name());
            String key = directory + "|" + hmac + "|" + e.mtime();
            String process = "crash".equals(directory) ? "kernel" : process(e.name());
            CrashEntry known = live.get(key);
            boolean isNew;
            if (known != null) {
                matched.add(key);
                isNew = known.size() != e.size(); // same name and mtime, changed size: a new file (section 7)
                seen.add(new CrashEntry(known.entryId(), directory, hmac, known.process(), e.size(), e.mtime()));
            } else {
                inserted.add(new NewCrashEntry(UUID.randomUUID().toString(), directory, hmac, process, e.size(), e.mtime(), baseline));
                isNew = true;
            }
            if (isNew && !baseline) {
                long[] agg = byProcess.computeIfAbsent(process, k -> new long[2]);
                agg[0]++;
                agg[1] = Math.max(agg[1], e.mtime());
            }
        }
        List<String> removed = new ArrayList<>();
        for (var e : live.entrySet()) if (!matched.contains(e.getKey())) removed.add(e.getValue().entryId());
        List<CrashAlert> alerts = new ArrayList<>();
        byProcess.forEach((process, agg) -> alerts.add(new CrashAlert(UUID.randomUUID().toString(), process, (int) agg[0], agg[1])));
        detail.put("entries", files);
        detail.put("baseline", baseline);
        detail.put("new_entries", baseline ? 0 : alerts.stream().mapToInt(CrashAlert::fileCount).sum());
        detail.put("removed_entries", removed.size());
        detail.put("alerts", alerts.size());
        detail.put("duration_ms", Math.max(0, clock.millis() - started));
        String outcome = !inserted.isEmpty() || !alerts.isEmpty() ? "COLLECTED" : "NO_NEW_DATA";
        run.outcome(outcome, null);
        recordCrash(run, runRow(outcome, null, detail),
                new CheckPointSystemLogRepository.CrashListingWrite(inserted, seen, removed, alerts));
    }

    /** Section 7: the process of a usermode dump name, when the name has the recognised form; else UNKNOWN. */
    static String process(String name) {
        Matcher m = CORE_NAME.matcher(name);
        if (m.matches() && KNOWN_PROCESSES.contains(m.group(1))) return m.group(1);
        return "UNKNOWN";
    }

    // ================================================================ C6/C7 + C8: daily whole files

    private void dailyItems(Run run, Gates g, StatParseResult stat) {
        boolean sixAm = new JobWindowPolicy(clock, 60).slotStart(clock.instant()).getHour() == 6;
        boolean sarBacklog = "BACKLOG".equals(repository.lastOutcome(run.deviceId, FileKind.SAR_DAILY.sourceKey()).orElse(null));
        if (sixAm || sarBacklog) wholeFile(run, g, FileKind.SAR_DAILY, Exec.LS_SA, g.sa(), stat);
        if (sixAm) wholeFile(run, g, FileKind.HCP_REPORT, Exec.LS_HCP, g.hcp(), stat);
    }

    private void wholeFile(Run run, Gates g, FileKind kind, Exec listingExec, GateResolution.Known listingGate,
            StatParseResult stat) {
        Map<String, Object> detail = new LinkedHashMap<>();
        Optional<FileRow> row = Optional.empty();
        List<SystemLogCollectionRepository.Gap> gaps = new ArrayList<>();
        String outcome, reason = null;
        long bytesRead = 0, durationMs = 0;
        int reads = 0;
        ArtefactStore.ArtefactMetadata meta = null;
        try {
            String output = exec(run, listingExec, listingGate);
            reads++;
            if (!stat.hasDeviceClock()) throw new Unknown("NO_DEVICE_CLOCK");
            long now = stat.deviceEpoch();
            CpLsListing.Result listing = CpLsListing.parse(output, kind.directory());
            Set<String> names = new HashSet<>();
            CpLsListing.Entry candidate = null;
            for (CpLsListing.Entry e : listing.entries()) {
                if (!e.regularFile() || !kind.nameAllowed(e.name())) continue;
                names.add(e.name());
                if (kind == FileKind.SAR_DAILY && !sarCandidate(e, now)) continue;
                if (candidate == null || e.mtime() > candidate.mtime()) candidate = e;
            }
            detail.put("listed_entries", listing.entries().size());
            if (candidate == null) {
                outcome = "ABSENT";
            } else {
                String hmac = fingerprint.of("cp-file|" + kind.sourceKey() + "|" + candidate.name());
                detail.put("size_bytes", candidate.size());
                if (kind == FileKind.HCP_REPORT && candidate.mtime() > now - SETTLE_SECONDS) {
                    outcome = "NO_NEW_DATA"; detail.put("not_settled", true);
                } else if (repository.fileStored(run.deviceId, kind.sourceKey(), hmac, candidate.mtime())) {
                    outcome = "NO_NEW_DATA"; detail.put("already_stored", true);
                } else if (candidate.size() > kind.maxBytes()) {
                    outcome = "TOO_LARGE";
                } else if (kind == FileKind.SAR_DAILY
                        && candidate.size() > Math.max(0, config.deviceBudgetBytes() - run.deviceSpent)) {
                    outcome = "BACKLOG";
                    gaps.add(new SystemLogCollectionRepository.Gap("BACKLOG", 0, candidate.size(), false, null, now,
                            detail(Map.of("reason", "DEVICE_BUDGET"))));
                } else if (volume.freeFraction() < SystemLogArtefactVolume.MIN_FREE_FRACTION) {
                    outcome = "STORAGE_REFUSED"; reason = "ARTEFACT_VOLUME_LOW_SPACE";
                } else {
                    long fetchStarted = clock.millis();
                    Fetched fetched = fetch(run, kind, names, candidate);
                    durationMs = Math.max(0, clock.millis() - fetchStarted);
                    reads++;
                    bytesRead = fetched.received;
                    if (fetched.tooLarge) {
                        outcome = "TOO_LARGE"; detail.put("grew_past_bound", true);
                    } else {
                        meta = fetched.meta;
                        boolean corrupt = kind == FileKind.HCP_REPORT && !fetched.readable;
                        List<String> flags = new ArrayList<>();
                        String nameTime = null;
                        if (kind == FileKind.HCP_REPORT) {
                            Matcher strict = HCP_STRICT.matcher(candidate.name());
                            if (strict.matches()) nameTime = hcpNameTime(strict);
                            else flags.add("NAME_SHAPE_UNEXPECTED");
                        }
                        int days = kind == FileKind.HCP_REPORT ? run.settings.hcpRetentionDays() : run.settings.rawRetentionDays();
                        Instant expires = clock.instant().plus(Duration.ofDays(days));
                        row = Optional.of(new FileRow(UUID.randomUUID().toString(), kind.sourceKey(), hmac, nameTime,
                                fetched.received, candidate.mtime(), corrupt ? "CORRUPT" : "STORED", flags,
                                fetched.contentHmac, manifest(run, meta, days), expires));
                        if (kind == FileKind.SAR_DAILY) run.deviceSpent += fetched.received;
                        detail.put("corrupt", corrupt);
                        detail.put("name_shape_unexpected", !flags.isEmpty());
                        outcome = "COLLECTED";
                    }
                }
            }
        } catch (Unknown unknown) {
            outcome = "UNKNOWN"; reason = unknown.code;
        }
        run.outcome(outcome, reason);
        if (reason != null) detail.put("reason", reason);
        var runRow = new SystemLogCollectionRepository.Run(outcome, bytesRead, row.map(FileRow::size).orElse(0L), reads, durationMs,
                detail(detail));
        boolean recorded;
        try {
            recorded = repository.recordFile(run.jobId, run.epoch, run.deviceId, kind.sourceKey(), runRow, row, gaps);
        } catch (RuntimeException notRecorded) {
            if (meta != null) volume.discard(meta.ref());
            throw notRecorded;
        }
        if (!recorded) {
            if (meta != null) volume.discard(meta.ref());
            throw new Abort(JobState.OUTCOME_UNKNOWN, "SYSTEM_LOG_NOT_RECORDED");
        }
    }

    /** Section 8.2: a {@code sar<DD>} written in the 30 h before the device clock whose DD is the day before the clock. */
    private static boolean sarCandidate(CpLsListing.Entry e, long deviceEpoch) {
        if (e.mtime() > deviceEpoch || e.mtime() <= deviceEpoch - SAR_WINDOW_SECONDS) return false;
        int dd = Integer.parseInt(e.name().substring(3));
        // The device's zone is not printed: yesterday is taken in UTC and in the estate's zone (Europe/Istanbul).
        Instant yesterday = Instant.ofEpochSecond(deviceEpoch).minus(Duration.ofDays(1));
        return dd == yesterday.atZone(ZoneOffset.UTC).getDayOfMonth()
                || dd == yesterday.atZone(JobWindowPolicy.ZONE_ID).getDayOfMonth();
    }

    private static String hcpNameTime(Matcher strict) {
        try {
            return LocalDateTime.of(2000 + Integer.parseInt(strict.group(3)), Integer.parseInt(strict.group(2)),
                    Integer.parseInt(strict.group(1)), Integer.parseInt(strict.group(4)), Integer.parseInt(strict.group(5)))
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (RuntimeException notADate) {
            return null;
        }
    }

    private record Fetched(ArtefactStore.ArtefactMetadata meta, long received, boolean tooLarge, boolean readable,
            String contentHmac) {
    }

    /**
     * C8 into a new artefact. sar streams straight into the artefact sink (gzip); an HCP archive (already compressed,
     * at most 16 MiB) is held to check its gzip and tar structure first. Over the bound, or a failed transfer, the
     * unfinished artefact is removed.
     */
    private Fetched fetch(Run run, FileKind kind, Set<String> names, CpLsListing.Entry candidate) {
        int step = run.step++;
        if (!attempts.findByJobAndStep(run.jobId, step).isEmpty()) throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
        String attempt = attempts.insertPreContact(run.jobId, run.epoch, step, CpSystemLogCollect.FETCH_GATE, "read", 1);
        if (attempt == null || !attempts.markBoundaryCrossed(attempt, run.epoch))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "PRE_CONTACT_UNCERTAIN");
        boolean hcp = kind == FileKind.HCP_REPORT;
        RangedFetchResult result;
        ArtefactStore.ArtefactMetadata meta = null;
        boolean readable = true;
        // The keyed content identity (V161 content_hmac) is computed over exactly the bytes received, as they stream.
        HostnameFingerprint.Keyed identity = fingerprint.content(SystemLogCollectionRepository.FILE_CONTENT_DOMAIN);
        try (ArtefactStore.ArtefactHandle handle = artefacts.open(run.deviceId, run.jobId, VENDOR, !hcp)) {
            ByteArrayOutputStream held = hcp ? new ByteArrayOutputStream() : null;
            OutputStream target = hcp ? held : handle.sink();
            OutputStream keyed = new java.io.FilterOutputStream(target) {
                @Override public void write(int b) throws IOException { write(new byte[] {(byte) b}, 0, 1); }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    out.write(b, off, len);
                    identity.update(b, off, len);
                }
            };
            result = CpSystemLogCollect.fetch(ssh, run.session, gates, kind, names, candidate.name(), candidate.size(), keyed);
            boolean ok = result.bytesUsable() && result.bytesReceived() <= kind.maxBytes();
            if (!attempts.writeOutcome(attempt, run.epoch, ok ? "MATCHED" : "EXPECTATION_UNMET",
                    ok ? null : "FETCH_" + result.status().name(), ok, result.bytesReceived(), null, null))
                throw new Abort(JobState.OUTCOME_UNKNOWN, "ATTEMPT_RECORD_FAILED");
            if (result.bytesUsable() && result.bytesReceived() > kind.maxBytes())
                return new Fetched(null, result.bytesReceived(), true, false, null);
            if (!result.bytesUsable()) throw new Unknown("FETCH_" + result.status().name());
            if (hcp) {
                byte[] archive = held.toByteArray();
                readable = TarGzIntegrity.readable(archive, archive.length);
                handle.sink().write(archive);
            }
            meta = handle.finish();
            return new Fetched(meta, result.bytesReceived(), false, readable, identity.finish());
        } catch (IOException writeFailure) {
            if (meta != null) volume.discard(meta.ref());
            throw new Unknown("ARTEFACT_WRITE_FAILED");
        }
    }

    // ================================================================ shared

    /** One gated, ledgered exec read (C1, C3, C6, C7); a timeout, a channel failure or an output over the bound is UNKNOWN. */
    private String exec(Run run, Exec exec, GateResolution.Known gate) {
        int step = run.step++;
        if (!attempts.findByJobAndStep(run.jobId, step).isEmpty()) throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
        String attempt = attempts.insertPreContact(run.jobId, run.epoch, step, gate.gateId(), "read", 1);
        if (attempt == null || !attempts.markBoundaryCrossed(attempt, run.epoch))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "PRE_CONTACT_UNCERTAIN");
        ExecResult result = ssh.exec(run.session, new ExecSpec(exec.command(), false, 0, exec.maxBytes()),
                Duration.ofSeconds(gate.timeoutS()));
        String failure = result instanceof ExecResult.Completed completed
                ? completed.output() == null ? "OUTPUT_UNAVAILABLE"
                : completed.output().getBytes(StandardCharsets.UTF_8).length > exec.maxBytes() ? "OUTPUT_LIMIT_EXCEEDED" : null
                : result instanceof ExecResult.TimedOut ? "TIMEOUT"
                : result instanceof ExecResult.ChannelFailed failed && "OUTPUT_LIMIT_EXCEEDED".equals(failed.reason())
                        ? "OUTPUT_LIMIT_EXCEEDED" : "CHANNEL_FAILED";
        long bytes = result instanceof ExecResult.Completed c && c.output() != null
                ? c.output().getBytes(StandardCharsets.UTF_8).length : 0L;
        if (!attempts.writeOutcome(attempt, run.epoch, failure == null ? "MATCHED" : "EXPECTATION_UNMET", failure,
                failure == null, bytes, null, null))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "ATTEMPT_RECORD_FAILED");
        if (failure != null) throw new Unknown(failure);
        // A non-zero exit is expected (an unmatched glob, a missing directory): the output is parsed by shape.
        return ((ExecResult.Completed) result).output();
    }

    private ArtefactStore.ArtefactMetadata write(Run run, byte[] bytes, boolean gzip) {
        try (ArtefactStore.ArtefactHandle handle = artefacts.open(run.deviceId, run.jobId, VENDOR, gzip)) {
            handle.sink().write(bytes);
            return handle.finish();
        } catch (IOException | RuntimeException writeFailure) {
            throw new Unknown("ARTEFACT_WRITE_FAILED");
        }
    }

    private BackupArtefactManifestRecord manifest(Run run, ArtefactStore.ArtefactMetadata meta, int retentionDays) {
        // A check_point manifest needs a software version (C7 section 3.3, for restores); a log artefact is never
        // restored, so the observed version is carried as information, "unknown" when none was observed.
        return new BackupArtefactManifestRecord(UUID.randomUUID().toString(), run.deviceId, Optional.empty(),
                ArtefactClass.SYSTEM_LOG, VENDOR, Optional.of(run.softwareVersion), fingerprint.of(run.hostnameSource),
                meta.plaintextSha256(), meta.plaintextBytes(), meta.ciphertextSha256(), meta.ciphertextBytes(), meta.keyId(),
                meta.wrappedDataKey(), ArtefactValidation.reachedWithoutRestore(ArtefactValidation.V1), "standard",
                Optional.of(clock.instant().plus(Duration.ofDays(retentionDays))), meta.ref().value(), Optional.empty());
    }

    private void recordSource(Run run, String sourceKey, SystemLogCollectionRepository.Run row, Optional<CpCursor> cursor,
            List<SystemLogCollectionRepository.Gap> gaps) {
        if (!repository.recordCpSource(run.jobId, run.epoch, run.deviceId, sourceKey, row, cursor, gaps))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "SYSTEM_LOG_NOT_RECORDED");
    }

    private void recordCrash(Run run, SystemLogCollectionRepository.Run row,
            CheckPointSystemLogRepository.CrashListingWrite write) {
        if (!repository.recordCrashListing(run.jobId, run.epoch, run.deviceId, row, write))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "SYSTEM_LOG_NOT_RECORDED");
    }

    private SystemLogCollectionRepository.Run runRow(String outcome, String reason, Map<String, Object> detail) {
        return runRow(outcome, reason, detail, detail.get("duration_ms") instanceof Number n ? n.longValue() : 0);
    }

    private SystemLogCollectionRepository.Run runRow(String outcome, String reason, Map<String, Object> detail, long durationMs) {
        Map<String, Object> d = new LinkedHashMap<>(detail);
        if (reason != null) d.put("reason", reason);
        long bytesRead = d.get("payload_bytes") instanceof Number n ? n.longValue() : 0;
        long stored = d.get("stored_bytes") instanceof Number n ? n.longValue() : 0;
        int reads = d.get("reads") instanceof Number n ? n.intValue() : 0;
        return new SystemLogCollectionRepository.Run(outcome, bytesRead, stored, reads, durationMs, detail(d));
    }

    private void finish(String jobId, long epoch, JobState state, String reason) {
        leases.transitionState(jobId, epoch, JobState.EXECUTING, state, ACTOR, "syslog_collect_finished", reason);
    }

    private String detail(Map<String, Object> detail) {
        try {
            return json.writeValueAsString(detail);
        } catch (IOException impossible) {
            return "{}";
        }
    }

    private String fileHmac(String deviceId, String path) {
        return fingerprint.of("cp-syslog-file|" + deviceId + "|" + path);
    }

    private static long lines(byte[] chunk) {
        long n = 0;
        for (byte b : chunk) if (b == '\n') n++;
        return chunk.length > 0 && chunk[chunk.length - 1] != '\n' ? n + 1 : n;
    }

    private static FileStat baseOf(StatParseResult stat, SourceFamily family) {
        for (FileStat f : stat.membersOf(family)) if (f.baseName().equals(family.baseName())) return f;
        throw new Unknown("BASE_FILE_NOT_LISTED");
    }

    private static CpSyslogRangedRead.Family readFamily(SourceFamily family) {
        for (CpSyslogRangedRead.Family f : CpSyslogRangedRead.Family.values()) if (f.sourceKey().equals(family.sourceKey())) return f;
        throw new Unknown("FAMILY_NOT_READABLE");
    }

    /** The stored snapshot: base names of the family pattern kept, any other name as a digest token. */
    private List<SnapshotEntry> snapshot(List<FileStat> members) {
        List<SnapshotEntry> out = new ArrayList<>();
        for (FileStat f : members) {
            String name = SAFE_NAME.matcher(f.baseName()).matches() ? f.baseName()
                    : "#" + fingerprint.of("cp-syslog-name|" + f.baseName()).substring(0, 16);
            out.add(new SnapshotEntry(name, Long.toString(f.inode()), f.size(), f.mtime()));
        }
        return out;
    }

    /** The planner's view of a stored cursor; null when the stored values cannot be represented (fail closed). */
    private static SourceCursor toPlanner(CpCursor stored) {
        try {
            List<FileStat> snapshot = new ArrayList<>();
            for (SnapshotEntry e : stored.familySnapshot())
                snapshot.add(new FileStat("/" + e.name(), Long.parseLong(e.inode()), e.size(), e.mtime()));
            return new SourceCursor(Long.parseLong(stored.inode()), stored.offsetBytes(), stored.anchorHmac(), snapshot,
                    stored.deviceEpochAtRead() == null ? 0 : stored.deviceEpochAtRead());
        } catch (RuntimeException unrepresentable) {
            return null;
        }
    }

    private static List<Gap> lossGaps(SourcePlan plan) {
        return plan.gaps().stream().filter(g -> g.kind() != GapKind.BACKLOG).toList();
    }

    private static List<Gap> addBaselineBytes(List<Gap> gaps, int skipped) {
        List<Gap> out = new ArrayList<>();
        for (Gap g : gaps) out.add(g.kind() == GapKind.INITIAL_BASELINE ? g.withAdditionalLostBytes(skipped) : g);
        return out;
    }

    private List<SystemLogCollectionRepository.Gap> toRows(List<Gap> gaps, String deviceId) {
        return gaps.stream().map(g -> row(g, deviceId)).toList();
    }

    /** A planner gap as a gap row: counts, suffix classes and name digests only (section 4.5, no names in clear). */
    private SystemLogCollectionRepository.Gap row(Gap gap, String deviceId) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (gap.knownLostIsUpperBound()) d.put("known_lost_is_upper_bound", true);
        if (gap.compressedBytesObserved() > 0) d.put("compressed_bytes_observed", gap.compressedBytesObserved());
        if (gap.unreadableSiblings() > 0) d.put("unreadable_siblings", gap.unreadableSiblings());
        List<String> digests = new ArrayList<>(), suffixes = new ArrayList<>();
        for (String path : gap.affectedPaths()) {
            digests.add(fileHmac(deviceId, path));
            String name = path.substring(path.lastIndexOf('/') + 1);
            SourceFamily family = SourceFamily.ofFileName(name);
            String suffix = family == null ? "OTHER" : name.substring(family.baseName().length());
            suffixes.add(SUFFIX.matcher(suffix).matches() ? suffix : "OTHER");
        }
        if (!digests.isEmpty()) { d.put("file_digests", digests); d.put("suffixes", suffixes); }
        return new SystemLogCollectionRepository.Gap(gap.kind().name(), gap.knownLostBytes(), gap.pendingBytes(),
                gap.extentUnknown(), gap.windowStartEpoch(), gap.windowEndEpoch(), detail(d));
    }
}
