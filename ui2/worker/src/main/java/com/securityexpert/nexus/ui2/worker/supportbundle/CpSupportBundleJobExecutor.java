package com.securityexpert.nexus.ui2.worker.supportbundle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.supportbundle.CpSupportBundle;
import com.securityexpert.nexus.ui2.jobs.supportbundle.CpSupportBundle.Exec;
import com.securityexpert.nexus.ui2.jobs.supportbundle.CpSupportBundleHeavy;
import com.securityexpert.nexus.ui2.jobs.supportbundle.CpSupportBundleHeavy.Step;
import com.securityexpert.nexus.ui2.jobs.transport.FetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchStreamResult;
import com.securityexpert.nexus.ui2.jobs.supportbundle.SupportBundleKind;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactValidation;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.SupportBundleRunStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;
import com.securityexpert.nexus.ui2.platform.TarStream;
import com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver;

/**
 * Support bundle collection, Check Point light items (step S2; design section 5.9, PO approval 2026-11-15): the
 * {@code cp_support_bundle} job on one Check Point Gaia gateway. One trusted SSH session, every command one after the
 * other and each resolved against its live V230 gate row, items cheapest first:
 *
 * <ol>
 *   <li>{@code cpdata_collector_present}: C0 only. Records PRESENT or ABSENT. The tool is never run (its run literal is not approved).</li>
 *   <li>{@code cp_gaia_extras}: C1, C2, C3, C4, C5 (the CPU and disk reads double as the prechecks). Each text is
 *       scrubbed by {@link SupportBundleTextRedactor} and the five files stored as one tar.gz artefact (1 MiB cap).</li>
 *   <li>{@code cp_hcp_latest}: the newest stored system-log HCP report of the last 24 hours is copied inside the vault
 *       (no device contact); otherwise C6 lists the directory and C7 fetches the newest archive (16 MiB cap).</li>
 * </ol>
 *
 * Command output never reaches a transcript, a log line or an outcome detail: details are closed codes and numbers.
 */
public final class CpSupportBundleJobExecutor {
    static final String ACTOR = JooqSupportBundleStore.WORKER_ACTOR;
    private static final String VENDOR = "check_point";
    /** PO 2026-11-15 (Q2): a bundle lives 14 days. */
    static final Duration TTL = Duration.ofDays(14);
    static final Duration HCP_VAULT_MAX_AGE = Duration.ofHours(24);
    private static final Pattern LS_ENTRY = Pattern.compile(
            "^-[rwxsStT-]{9}[.+@]?\\s+[0-9]{1,9}\\s+[0-9]{1,18}\\s+([0-9]{1,18})\\s+([0-9]{1,11})\\s+(.+)$");
    private static final Pattern HCP_STRICT = Pattern.compile(
            "hcp_report_[A-Za-z0-9._-]+_([0-9]{2})_([0-9]{2})_([0-9]{2})_([0-9]{2})_([0-9]{2})\\.tar\\.gz");

    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DeviceRepository devices;
    private final DeviceTransport ssh;
    private final GateRegistryPort gates;
    private final SupportBundleRunStore store;
    private final ArtefactStore artefacts;
    private final HostnameFingerprint fingerprint;
    private final SupportBundleTextRedactor redactor = new SupportBundleTextRedactor();
    private final Clock clock;
    private final Sleeper sleeper;
    private final ObjectMapper json = new ObjectMapper();

    /** Waits between polls; a test double returns at once, production sleeps. An interrupt ends the wait. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    public CpSupportBundleJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceTransport ssh, GateRegistryPort gates, SupportBundleRunStore store, ArtefactStore artefacts,
            HostnameFingerprint fingerprint) {
        this(leases, attempts, devices, ssh, gates, store, artefacts, fingerprint, Clock.systemUTC());
    }

    CpSupportBundleJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceTransport ssh, GateRegistryPort gates, SupportBundleRunStore store, ArtefactStore artefacts,
            HostnameFingerprint fingerprint, Clock clock) {
        this(leases, attempts, devices, ssh, gates, store, artefacts, fingerprint, clock, d -> Thread.sleep(d.toMillis()));
    }

    CpSupportBundleJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceTransport ssh, GateRegistryPort gates, SupportBundleRunStore store, ArtefactStore artefacts,
            HostnameFingerprint fingerprint, Clock clock, Sleeper sleeper) {
        this.sleeper = sleeper;
        this.leases = leases; this.attempts = attempts; this.devices = devices; this.ssh = ssh; this.gates = gates;
        this.store = store; this.artefacts = artefacts; this.fingerprint = fingerprint; this.clock = clock;
    }

    /** A step the job could not record or a lease it lost: the job stops. */
    private static final class Abort extends RuntimeException {
        final JobState state; final String code;
        Abort(JobState state, String code) { super(code, null, false, false); this.state = state; this.code = code; }
    }

    /** One command's typed failure: the item records it and the next item still runs. */
    private static final class Unknown extends RuntimeException {
        final String code;
        Unknown(String code) { super(code, null, false, false); this.code = code; }
    }

    private static final class Run {
        final String jobId; final long epoch; final String deviceId; final TransportSession session; final String version;
        final String hostSource; final Instant expiresAt; final Map<String, Object> precheck = new LinkedHashMap<>();
        final List<String> gateIds = new ArrayList<>();
        int step;
        String scope = CpSupportBundle.PLATFORM_ROLE_SCOPE;
        /** Step S6 state of the run: the prechecks, the presence read, bytes stored, and a work directory that stays on the device. */
        boolean cpuRead, dfRead, workDirLeft, stopped;
        Integer cpuMax;
        Long freeBytes;
        Boolean cpdataPresent;
        long storedBytes;
        Run(String jobId, long epoch, String deviceId, TransportSession session, String version, String hostSource,
                Instant expiresAt) {
            this.jobId = jobId; this.epoch = epoch; this.deviceId = deviceId; this.session = session; this.version = version;
            this.hostSource = hostSource; this.expiresAt = expiresAt;
        }
    }

    public void execute(String jobId, long epoch, String deviceId, String host, int port, String credentialRef) {
        Optional<RequestRow> request = store == null ? Optional.empty() : store.findByJob(jobId);
        Optional<DeviceRecord> device = devices.find(deviceId);
        var endpoint = devices.findEndpointByDeviceId(deviceId)
                .filter(e -> deviceId.equals(e.deviceId()) && "ssh_exec".equals(e.transportKind()));
        boolean management = device.isPresent() && "management_server".equals(device.get().role());
        String scope = management ? CpSupportBundle.MANAGEMENT_SCOPE : CpSupportBundle.PLATFORM_ROLE_SCOPE;
        boolean gatesKnown = true;
        if (!management) {
            gatesKnown = CpSupportBundle.sftpGate(gates).isPresent();
            for (Exec e : Exec.values()) gatesKnown &= CpSupportBundle.gate(e, gates).isPresent();
        }
        // A request holding a heavy item needs every C8-C20 row signed off under its scope before anything starts.
        boolean heavyRequested = request.isPresent() && store.items(request.get().id()).stream()
                .anyMatch(i -> SupportBundleKind.fromId(i.kind()).map(SupportBundleKind::heavy).orElse(false));
        if (management || heavyRequested) gatesKnown &= CpSupportBundleHeavy.allGatesKnown(scope, gates);
        if (request.isEmpty() || device.isEmpty() || !device.get().permitsReadCollection()
                || !VENDOR.equals(device.get().vendorHint()) || !("gateway".equals(device.get().role()) || management)
                || endpoint.isEmpty() || artefacts == null || !gatesKnown || host == null || credentialRef == null) {
            leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.REJECTED, ACTOR, "support_bundle_claim_check",
                    "SUPPORT_BUNDLE_UNAVAILABLE");
            request.ifPresent(r -> store.finishRequest(r.id(), "FAILED", "FAILED", "{}", clock.instant().plus(TTL)));
            return;
        }
        RequestRow req = request.get();
        if (!leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR, "support_bundle_start")) return;
        if (!store.markRunning(req.id())) {
            finish(jobId, epoch, JobState.COMPLETED, "SUPPORT_BUNDLE_NOT_QUEUED");
            return;
        }
        List<ItemRow> items = store.items(req.id()).stream()
                .sorted(Comparator.comparingInt(i -> SupportBundleKind.fromId(i.kind()).map(SupportBundleKind::order).orElse(99)))
                .toList();
        TransportSession session = null;
        try {
            if (!attempts.findByJobAndStep(jobId, 0).isEmpty()) throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
            var summary = devices.findSummary(deviceId);
            String version = summary.flatMap(DeviceSummaryRecord::observedSoftwareVersion).orElse("unknown");
            String hostSource = summary.flatMap(DeviceSummaryRecord::observedHostname).orElse(deviceId);
            var connected = ssh.connect(new ConnectionTarget(UUID.randomUUID().toString(), host, port),
                    new ConnectSpec(credentialRef, PersistedManagementEndpointTrustResolver.scopeRef(host, port), Optional.empty()),
                    Duration.ofSeconds(30));
            if (!(connected instanceof ConnectResult.Authenticated authenticated)) {
                for (ItemRow item : items) store.finishItem(item.id(), "FAILED", "CONNECTION_FAILED", null, List.of());
                store.finishRequest(req.id(), "FAILED", "FAILED", "{}", clock.instant().plus(TTL));
                finish(jobId, epoch, JobState.FAILED, "SUPPORT_BUNDLE_CONNECTION_FAILED");
                return;
            }
            session = authenticated.session();
            Run run = new Run(jobId, epoch, deviceId, session, version, hostSource, clock.instant().plus(TTL));
            run.scope = scope;
            int stored = 0, completed = 0;
            for (ItemRow item : items) {
                store.markItemRunning(item.id());
                run.gateIds.clear();
                String result;
                if (run.stopped) {
                    store.finishItem(item.id(), "CANCELLED", "SESSION_CLOSED_EARLIER", null, List.of());
                    continue;
                }
                try {
                    result = switch (SupportBundleKind.fromId(item.kind()).orElseThrow()) {
                        case CPDATA_COLLECTOR_PRESENT -> cpdataPresent(run, item);
                        case CP_GAIA_EXTRAS -> gaiaExtras(run, item);
                        case CP_HCP_LATEST -> hcpLatest(run, item, req);
                        case CP_HCP_RUN, CP_CPINFO, CP_CPDATA_COLLECTOR -> heavy(run, item, req,
                                SupportBundleKind.fromId(item.kind()).orElseThrow());
                        default -> { store.finishItem(item.id(), "UNSUPPORTED", "KIND_NOT_AVAILABLE", null, List.of()); yield "UNSUPPORTED"; }
                    };
                } catch (Unknown unknown) {
                    store.finishItem(item.id(), "FAILED", unknown.code, null, List.copyOf(run.gateIds));
                    result = "FAILED";
                }
                if ("COMPLETED".equals(result)) completed++;
                if ("STORED".equals(result)) { stored++; completed++; }
            }
            String state = completed == items.size() ? "COMPLETED" : (completed > 0 ? "PARTIAL" : "FAILED");
            store.finishRequest(req.id(), state, state, json.writeValueAsString(run.precheck), run.expiresAt);
            finish(jobId, epoch, "FAILED".equals(state) ? JobState.FAILED : JobState.COMPLETED, "SUPPORT_BUNDLE_" + state);
        } catch (Abort abort) {
            // A stopped job must not leave its request RUNNING (it would hold the device); an item that was mid-run keeps its recorded work directory.
            store.finishRequest(req.id(), "FAILED", "FAILED", "{}", clock.instant().plus(TTL));
            finish(jobId, epoch, abort.state, abort.code);
        } catch (Exception failure) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(failure);
            store.finishRequest(req.id(), "FAILED", "FAILED", "{}", clock.instant().plus(TTL));
            finish(jobId, epoch, JobState.OUTCOME_UNKNOWN, "SUPPORT_BUNDLE_OUTCOME_UNCERTAIN");
        } finally {
            if (session != null) ssh.disconnect(session);
        }
    }

    // ------------------------------------------------------------------ items

    /** C0. PRESENT / ABSENT; the tool itself is never run. */
    private String cpdataPresent(Run run, ItemRow item) {
        run.gateIds.add(Exec.CPDATA_PRESENT.gateId());
        ExecResult.Completed result = exec(run, Exec.CPDATA_PRESENT, 4096);
        boolean present = result.exitStatus() == 0 && !result.output().isBlank();
        store.finishItem(item.id(), "COMPLETED", present ? "PRESENT" : "ABSENT", null, List.copyOf(run.gateIds));
        run.precheck.put("cpdata_collector_present", present);
        run.cpdataPresent = present;
        return "COMPLETED";
    }

    /** C1-C5: five text files, each scrubbed, one tar.gz artefact. */
    private String gaiaExtras(Run run, ItemRow item) throws IOException {
        Exec[] order = {Exec.SHOW_VERSION_ALL, Exec.SHOW_ASSET_ALL, Exec.CPHAPROB_STAT, Exec.CPU_MULTI, Exec.DF_VAR_LOG};
        String[] files = {"show-version-all.txt", "show-asset-all.txt", "cphaprob-stat.txt", "multi-cpu.txt", "df-var-log.txt"};
        List<TarStream.Entry> entries = new ArrayList<>();
        long total = 0;
        int failed = 0;
        for (int i = 0; i < order.length; i++) {
            run.gateIds.add(order[i].gateId());
            try {
                String text = exec(run, order[i], CpSupportBundle.COMMAND_MAX_BYTES).output();
                if (order[i] == Exec.CPU_MULTI) recordCpu(run, text);
                if (order[i] == Exec.DF_VAR_LOG) recordDf(run, text);
                byte[] clean = redactor.redact(text.getBytes(StandardCharsets.UTF_8));
                total += clean.length;
                entries.add(new TarStream.Entry("items/cp_gaia_extras/" + files[i], clean));
            } catch (Unknown unknown) {
                failed++;
            } catch (SupportBundleTextRedactor.RedactionFailed redaction) {
                store.finishItem(item.id(), "REDACTION_FAILED", "REDACTION_FAILED", null, List.copyOf(run.gateIds));
                return "REDACTION_FAILED";
            }
        }
        if (entries.isEmpty()) throw new Unknown("ALL_COMMANDS_FAILED");
        if (total > CpSupportBundle.GAIA_EXTRAS_MAX_BYTES) {
            store.finishItem(item.id(), "TOO_LARGE", "TOO_LARGE", "bytes " + total + " over " + CpSupportBundle.GAIA_EXTRAS_MAX_BYTES,
                    List.copyOf(run.gateIds));
            return "TOO_LARGE";
        }
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(TarStream.toBytes(entries, clock.instant().getEpochSecond()));
        }
        ArtefactStore.ArtefactMetadata meta;
        try (ArtefactStore.ArtefactHandle handle = artefacts.open(run.deviceId, run.jobId, VENDOR, false)) {
            handle.sink().write(gz.toByteArray());
            meta = handle.finish();
        } catch (IOException e) {
            throw new Unknown("ARTEFACT_WRITE_FAILED");
        }
        store.completeItem(item.id(), manifest(run, meta), failed == 0 ? "COMPLETED" : "PARTIAL",
                failed == 0 ? "COMPLETED" : "SOME_COMMANDS_FAILED", "items/cp_gaia_extras/", SupportBundleTextRedactor.RULESET,
                List.copyOf(run.gateIds));
        return "STORED";
    }

    /** The newest HCP archive: a vault copy when one under 24 hours old exists, else C6 and C7. */
    private String hcpLatest(Run run, ItemRow item, RequestRow request) throws IOException {
        Optional<JooqSupportBundleStore.HcpCopySource> source =
                store.newestStoredHcpReport(run.deviceId, clock.instant().minus(HCP_VAULT_MAX_AGE));
        if (source.isPresent() && source.get().size() <= CpSupportBundle.HCP_MAX_BYTES) {
            ArtefactStore.ArtefactMetadata meta;
            try (InputStream in = artefacts.retrieve(new ArtefactRef(source.get().artefactRef()),
                    source.get().wrappedDataKey(), false);
                    ArtefactStore.ArtefactHandle handle = artefacts.open(run.deviceId, run.jobId, VENDOR, false)) {
                GzipMagic magic = new GzipMagic(handle.sink());
                in.transferTo(magic);
                if (!magic.ok()) throw new Unknown("NOT_GZIP");
                meta = handle.finish();
            } catch (IOException e) {
                throw new Unknown("VAULT_COPY_FAILED");
            }
            store.completeItem(item.id(), manifest(run, meta), "COMPLETED", "VAULT_COPY",
                    "items/cp_hcp_latest/hcp_report.tar.gz", "none", List.copyOf(run.gateIds));
            return "STORED";
        }
        run.gateIds.add(Exec.LS_HCP_LAST.gateId());
        String listing = exec(run, Exec.LS_HCP_LAST, CpSupportBundle.COMMAND_MAX_BYTES).output();
        String name = null;
        long size = -1, newest = -1;
        for (String raw : listing.split("\\R")) {
            Matcher m = LS_ENTRY.matcher(raw.strip());
            if (!m.matches()) continue;
            String candidate = m.group(3);
            long mtime = Long.parseLong(m.group(2));
            if (candidate.endsWith(".tar.gz") && CpSupportBundle.HCP_NAME.matcher(candidate).matches() && mtime > newest) {
                newest = mtime; name = candidate; size = Long.parseLong(m.group(1));
            }
        }
        if (name == null) {
            store.finishItem(item.id(), "COMPLETED", "ABSENT", null, List.copyOf(run.gateIds));
            return "COMPLETED";
        }
        if (size > CpSupportBundle.HCP_MAX_BYTES) {
            store.finishItem(item.id(), "TOO_LARGE", "TOO_LARGE", "listed size " + size, List.copyOf(run.gateIds));
            return "TOO_LARGE";
        }
        GateResolution.Known gate = CpSupportBundle.sftpGate(gates).orElseThrow(() -> new Unknown("GATE_NOT_KNOWN"));
        run.gateIds.add(gate.gateId());
        int step = run.step++;
        if (!attempts.findByJobAndStep(run.jobId, step).isEmpty()) throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
        String attempt = attempts.insertPreContact(run.jobId, run.epoch, step, gate.gateId(), "read", 1);
        if (attempt == null || !attempts.markBoundaryCrossed(attempt, run.epoch))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "PRE_CONTACT_UNCERTAIN");
        ArtefactStore.ArtefactMetadata meta = null;
        RangedFetchResult result;
        boolean magicOk;
        boolean tooLarge;
        try (ArtefactStore.ArtefactHandle handle = artefacts.open(run.deviceId, run.jobId, VENDOR, false)) {
            GzipMagic magic = new GzipMagic(handle.sink());
            result = ssh.fetchBounded(run.session, new RangedFetchSpec(CpSupportBundle.HCP_DIRECTORY + "/" + name, 0,
                    CpSupportBundle.HCP_MAX_BYTES + 1), CpSupportBundle.timeout(gate), magic, "cp_hcp_report");
            tooLarge = result.bytesUsable() && result.bytesReceived() > CpSupportBundle.HCP_MAX_BYTES;
            magicOk = magic.ok();
            boolean ok = result.bytesUsable() && !tooLarge && magicOk;
            if (!attempts.writeOutcome(attempt, run.epoch, ok ? "MATCHED" : "EXPECTATION_UNMET",
                    ok ? null : "FETCH_" + result.status().name(), ok, result.bytesReceived(), null, null))
                throw new Abort(JobState.OUTCOME_UNKNOWN, "ATTEMPT_RECORD_FAILED");
            if (ok) meta = handle.finish();
        } catch (IOException e) {
            throw new Unknown("ARTEFACT_WRITE_FAILED");
        }
        if (tooLarge) {
            store.finishItem(item.id(), "TOO_LARGE", "TOO_LARGE", "received over " + CpSupportBundle.HCP_MAX_BYTES,
                    List.copyOf(run.gateIds));
            return "TOO_LARGE";
        }
        if (!result.bytesUsable()) throw new Unknown("FETCH_" + result.status().name());
        if (!magicOk || meta == null) throw new Unknown("NOT_GZIP");
        store.completeItem(item.id(), manifest(run, meta), "COMPLETED", "COMPLETED", "items/cp_hcp_latest/" + safeName(name),
                "none", List.copyOf(run.gateIds));
        return "STORED";
    }

    /**
     * The stored entry name never keeps the host: a name of the measured form keeps only its timestamp, anything else
     * becomes {@code hcp_report.tar.gz}.
     */
    static String safeName(String listed) {
        Matcher m = HCP_STRICT.matcher(listed);
        return m.matches() ? "hcp_report_" + m.group(1) + "_" + m.group(2) + "_" + m.group(3) + "_" + m.group(4) + "_"
                + m.group(5) + ".tar.gz" : "hcp_report.tar.gz";
    }

    /** Highest per-core usage in a {@code cpstat os -f multi_cpu} table, or null when the shape is not recognised. */
    static Integer maxCorePercent(String output) {
        Integer max = null;
        int usageColumn = -1;
        for (String line : output.split("\\R")) {
            if (!line.contains("|")) continue;
            String[] cells = line.split("\\|", -1);
            if (usageColumn < 0) {
                for (int i = 0; i < cells.length; i++) if (cells[i].strip().startsWith("Usage")) usageColumn = i;
                continue;
            }
            if (usageColumn < cells.length && cells[usageColumn].strip().matches("[0-9]{1,3}")) {
                int value = Integer.parseInt(cells[usageColumn].strip());
                if (value <= 100) max = max == null ? value : Math.max(max, value);
            }
        }
        return max;
    }

    // ------------------------------------------------------------------ heavy items (step S6)

    private void recordCpu(Run run, String text) {
        run.cpuMax = maxCorePercent(text);
        run.cpuRead = true;
        run.precheck.put("cpu_max_core_pct", run.cpuMax);
    }

    private void recordDf(Run run, String text) {
        run.freeBytes = com.securityexpert.nexus.ui2.worker.backup.BackupCapabilityExecutor.parseDfAvailableBytes(text).orElse(null);
        run.dfRead = true;
        run.precheck.put("disk_free_bytes", run.freeBytes);
    }

    /** C1 and C2 once per run (the vendor CPU rule and the local disk heuristic); a read that fails leaves the value unknown. */
    private void ensurePrechecks(Run run) {
        if (!run.cpuRead) {
            run.gateIds.add(Exec.CPU_MULTI.gateId(run.scope));
            try { recordCpu(run, exec(run, Exec.CPU_MULTI, CpSupportBundle.COMMAND_MAX_BYTES).output()); }
            catch (Unknown unknown) { run.cpuRead = true; run.cpuMax = null; }
        }
        if (!run.dfRead) {
            run.gateIds.add(Exec.DF_VAR_LOG.gateId(run.scope));
            try { recordDf(run, exec(run, Exec.DF_VAR_LOG, CpSupportBundle.COMMAND_MAX_BYTES).output()); }
            catch (Unknown unknown) { run.dfRead = true; run.freeBytes = null; }
        }
    }

    /** What a heavy item ended as, before the device directory is dealt with. */
    private record HeavyEnd(String state, String code, String detail, ArtefactStore.ArtefactMetadata meta, String entry, boolean remove) {
        static HeavyEnd failed(String state, String code, String detail) { return new HeavyEnd(state, code, detail, null, null, true); }
        static HeavyEnd leftOnDevice(String state, String code, String detail) { return new HeavyEnd(state, code, detail, null, null, false); }
    }

    private GateResolution.Known stepGate(Run run, Step step) {
        return step.gate(run.scope, gates).orElseThrow(() -> new Unknown("GATE_NOT_KNOWN"));
    }

    private Sent sendStep(Run run, Step step, String command, int maxBytes) {
        GateResolution.Known gate = stepGate(run, step);
        run.gateIds.add(gate.gateId());
        return send(run, gate, command, step.writes() ? "recovery-write" : "read", maxBytes);
    }

    /** One heavy item: HCP run, cpinfo or the optional cpdata_collector run, in its own work directory. */
    private String heavy(Run run, ItemRow item, RequestRow req, SupportBundleKind kind) throws IOException {
        if (run.workDirLeft) {
            store.finishItem(item.id(), "REFUSED_PRECHECK", "PRIOR_WORKDIR_LEFT", "an earlier item left its work directory on the device",
                    List.copyOf(run.gateIds));
            return "REFUSED_PRECHECK";
        }
        ensurePrechecks(run);
        if (run.cpuMax == null || run.cpuMax > CpSupportBundleHeavy.MAX_CORE_PERCENT) {
            store.finishItem(item.id(), "REFUSED_PRECHECK", "CPU_HIGH_OR_UNKNOWN",
                    run.cpuMax == null ? "per-core usage UNKNOWN" : "highest core " + run.cpuMax + "% over " + CpSupportBundleHeavy.MAX_CORE_PERCENT + "%",
                    List.copyOf(run.gateIds));
            return "REFUSED_PRECHECK";
        }
        long need = kind == SupportBundleKind.CP_HCP_RUN ? CpSupportBundleHeavy.HCP_MIN_FREE_BYTES : CpSupportBundleHeavy.CPINFO_MIN_FREE_BYTES;
        if (run.freeBytes == null || run.freeBytes < need) {
            store.finishItem(item.id(), "REFUSED_PRECHECK", "DISK_LOW_OR_UNKNOWN",
                    run.freeBytes == null ? "free space in /var/log UNKNOWN" : "free " + run.freeBytes + " bytes under " + need,
                    List.copyOf(run.gateIds));
            return "REFUSED_PRECHECK";
        }
        if (run.storedBytes >= CpSupportBundleHeavy.REQUEST_AGGREGATE_MAX_BYTES) {
            store.finishItem(item.id(), "TOO_LARGE", "REQUEST_CAP", "25 GiB request cap reached", List.copyOf(run.gateIds));
            return "TOO_LARGE";
        }
        Integer vs = item.vsId();
        if (vs != null && (!"gateway".equals(roleOf(run)) || !store.virtualSystemIds(run.deviceId).contains(vs))) {
            store.finishItem(item.id(), "FAILED", "INVALID_VS", null, List.copyOf(run.gateIds));
            return "FAILED";
        }
        if (kind == SupportBundleKind.CP_CPDATA_COLLECTOR) {
            if (run.cpdataPresent == null) {
                run.gateIds.add(Exec.CPDATA_PRESENT.gateId(run.scope));
                try {
                    ExecResult.Completed present = exec(run, Exec.CPDATA_PRESENT, 4096);
                    run.cpdataPresent = present.exitStatus() == 0 && !present.output().isBlank();
                } catch (Unknown unknown) { run.cpdataPresent = false; }
            }
            if (!run.cpdataPresent) {
                store.finishItem(item.id(), "UNSUPPORTED", "CPDATA_COLLECTOR_ABSENT", null, List.copyOf(run.gateIds));
                return "UNSUPPORTED";
            }
            Sent list = sendStep(run, Step.CPDATA_LIST, CpSupportBundleHeavy.render(Step.CPDATA_LIST, "/var/log/nexus-sb-000000000000", null, null),
                    CpSupportBundle.COMMAND_MAX_BYTES);
            if (list.failure() != null || list.exit() != 0 || list.output().isBlank()) {
                store.finishItem(item.id(), "UNSUPPORTED", "CPDATA_LIST_FAILED", null, List.copyOf(run.gateIds));
                return "UNSUPPORTED";
            }
        }
        String dir = CpSupportBundleHeavy.workDir(run.jobId, item.ordinal());
        store.markWorkDir(req.id(), dir);   // the point of no return: the name is on record before the device is touched
        HeavyEnd end;
        try {
            end = heavyBody(run, item, kind, vs, dir);
        } catch (Unknown unknown) {
            end = HeavyEnd.failed("FAILED", unknown.code, null);
        }
        boolean cleaned = false;
        if (end.remove()) {
            cleaned = removeWorkDir(run, dir);
            if (cleaned) store.markWorkDirCleaned(req.id()); else run.workDirLeft = true;
        } else {
            run.workDirLeft = true;
        }
        if (end.meta() != null) {
            store.completeItem(item.id(), manifest(run, end.meta()), cleaned ? "COMPLETED" : "PARTIAL",
                    cleaned ? "COMPLETED" : "CLEANUP_FAILED", end.entry(), "none", List.copyOf(run.gateIds));
            run.storedBytes += end.meta().plaintextBytes();
            return "STORED";
        }
        store.finishItem(item.id(), end.state(), end.code(), end.detail(), List.copyOf(run.gateIds));
        return end.state();
    }

    private String roleOf(Run run) {
        return CpSupportBundle.MANAGEMENT_SCOPE.equals(run.scope) ? "management_server" : "gateway";
    }

    /** C8, start, poll, list, bundle, digest, fetch and compare. The caller removes the directory when {@code remove} is set. */
    private HeavyEnd heavyBody(Run run, ItemRow item, SupportBundleKind kind, Integer vs, String dir) throws IOException {
        Sent mkdir = sendStep(run, Step.MKDIR, CpSupportBundleHeavy.render(Step.MKDIR, dir, null, null), 4096);
        if (mkdir.uncertain()) return HeavyEnd.leftOnDevice("OUTCOME_UNKNOWN", "MKDIR_UNCERTAIN", null);
        if (mkdir.exit() != 0) return HeavyEnd.failed("FAILED", "MKDIR_FAILED", null);

        boolean management = CpSupportBundle.MANAGEMENT_SCOPE.equals(run.scope);
        Step start;
        String tool;
        long limit;
        switch (kind) {
            case CP_HCP_RUN -> { start = vs != null ? Step.HCP_START_VS : Step.HCP_START; tool = "hcp"; limit = CpSupportBundleHeavy.HCP_BUNDLE_MAX_BYTES; }
            case CP_CPINFO -> {
                start = vs != null ? Step.CPINFO_START_VS : management && !store.multiDomainServer(run.deviceId) ? Step.CPINFO_START_SMS : Step.CPINFO_START;
                tool = "cpinfo"; limit = CpSupportBundleHeavy.CPINFO_MAX_BYTES;
            }
            default -> { start = Step.CPDATA_RUN; tool = "cpsdc"; limit = CpSupportBundleHeavy.CPDATA_MAX_BYTES; }
        }
        // Started once and never retried. A timed out or lost start may well have started the tool: it is polled like any other.
        Sent started = sendStep(run, start, CpSupportBundleHeavy.render(start, dir, vs == null ? null : vs.toString(), null), 4096);
        if (started.completed() && started.exit() != 0) return HeavyEnd.failed("FAILED", "START_REFUSED", null);

        Integer rc = null;
        if (kind == SupportBundleKind.CP_CPDATA_COLLECTOR) {
            // The approved C15 reads hcp.rc and cpinfo.rc only: cpsdc.rc has no approved poll. The tool is bounded on the device
            // by "timeout 1800", so the run waits that long and then lists the directory once (C16).
            Duration waited = Duration.ZERO, total = CpSupportBundleHeavy.CPDATA_DEVICE_TIMEOUT.plusSeconds(60);
            while (waited.compareTo(total) < 0) {
                if (!pause(CpSupportBundleHeavy.POLL_INTERVAL)) return HeavyEnd.leftOnDevice("OUTCOME_UNKNOWN", "INTERRUPTED", null);
                waited = waited.plus(CpSupportBundleHeavy.POLL_INTERVAL);
            }
        } else {
            Duration elapsed = Duration.ZERO;
            int unanswered = 0;
            while (rc == null && elapsed.compareTo(CpSupportBundleHeavy.HEAVY_DEADLINE) < 0) {
                if (!pause(CpSupportBundleHeavy.POLL_INTERVAL)) return HeavyEnd.leftOnDevice("OUTCOME_UNKNOWN", "INTERRUPTED", null);
                elapsed = elapsed.plus(CpSupportBundleHeavy.POLL_INTERVAL);
                Sent poll = sendStep(run, Step.POLL_RC, CpSupportBundleHeavy.renderPoll(dir, tool), 4096);
                if (poll.completed() && poll.exit() == 0 && poll.output().strip().matches("[0-9]{1,3}")) rc = Integer.parseInt(poll.output().strip());
                else unanswered = poll.completed() ? 0 : unanswered + 1;
            }
            run.precheck.put("poll_unanswered_streak_" + item.ordinal(), unanswered);
            if (rc == null) return HeavyEnd.leftOnDevice("OUTCOME_UNKNOWN", "NO_EXIT_CODE_BY_DEADLINE", "work directory left on the device");
            if (rc == 127) return HeavyEnd.failed("UNSUPPORTED", "TOOL_NOT_INSTALLED", null);
            if (rc != 0) return HeavyEnd.failed("FAILED", "TOOL_EXIT_" + rc, null);
        }

        if (kind == SupportBundleKind.CP_HCP_RUN) {
            // The report HCP just wrote is the newest listed one; it is copied into the work directory by its listed name (C6, C11).
            run.gateIds.add(Exec.LS_HCP_LAST.gateId(run.scope));
            String name = newestHcpReport(exec(run, Exec.LS_HCP_LAST, CpSupportBundle.COMMAND_MAX_BYTES).output());
            if (name == null) return HeavyEnd.failed("FAILED", "NO_HCP_REPORT", null);
            Sent copy = sendStep(run, Step.COPY_HCP, CpSupportBundleHeavy.render(Step.COPY_HCP, dir, null, name), 4096);
            if (copy.failure() != null || copy.exit() != 0) return HeavyEnd.failed("FAILED", "COPY_FAILED", null);
        }
        Sent listed = sendStep(run, Step.LIST_WORKDIR, CpSupportBundleHeavy.render(Step.LIST_WORKDIR, dir, null, null),
                CpSupportBundle.COMMAND_MAX_BYTES);
        if (listed.failure() != null || listed.exit() != 0 || listed.output().isBlank()) return HeavyEnd.failed("FAILED", "WORKDIR_LIST_FAILED", null);
        if (kind == SupportBundleKind.CP_CPDATA_COLLECTOR && !(listed.output().contains("cpsdc.rc") && listed.output().contains("cpsdc.tgz"))) {
            return HeavyEnd.failed("TIMED_OUT", "NO_CPDATA_ARCHIVE", "cpsdc.rc and cpsdc.tgz not both listed after the device timeout");
        }
        Sent bundle = sendStep(run, Step.BUNDLE, CpSupportBundleHeavy.render(Step.BUNDLE, dir, null, null), 4096);
        if (bundle.uncertain()) return HeavyEnd.leftOnDevice("OUTCOME_UNKNOWN", "BUNDLE_UNCERTAIN", null);
        if (bundle.exit() != 0) return HeavyEnd.failed("FAILED", "BUNDLE_FAILED", null);
        Sent digest = sendStep(run, Step.SHA256, CpSupportBundleHeavy.render(Step.SHA256, dir, null, null), 4096);
        String deviceDigest = digest.failure() == null && digest.exit() == 0 ? firstHex64(digest.output()) : null;

        // C19: the bundle streams straight into the vault; nothing is held whole in memory.
        GateResolution.Known sftp = CpSupportBundleHeavy.sftpGate(run.scope, gates).orElseThrow(() -> new Unknown("GATE_NOT_KNOWN"));
        run.gateIds.add(sftp.gateId());
        int step = run.step++;
        if (!attempts.findByJobAndStep(run.jobId, step).isEmpty()) throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
        String attempt = attempts.insertPreContact(run.jobId, run.epoch, step, sftp.gateId(), "read", 1);
        if (attempt == null || !attempts.markBoundaryCrossed(attempt, run.epoch)) throw new Abort(JobState.OUTCOME_UNKNOWN, "PRE_CONTACT_UNCERTAIN");
        long cap = Math.min(limit, CpSupportBundleHeavy.REQUEST_AGGREGATE_MAX_BYTES - run.storedBytes);
        FetchStreamResult fetched;
        ArtefactStore.ArtefactMetadata meta = null;
        try (ArtefactStore.ArtefactHandle handle = artefacts.open(run.deviceId, run.jobId, VENDOR, false)) {
            fetched = ssh.fetchStreaming(run.session, new FetchSpec(CpSupportBundleHeavy.renderBundlePath(dir), cap + 1),
                    Duration.ofSeconds(CpSupportBundleHeavy.SFTP_TIMEOUT_S), handle.sink());
            boolean ok = fetched instanceof FetchStreamResult.Fetched f && f.bytesTransferred() <= cap;
            if (!attempts.writeOutcome(attempt, run.epoch, ok ? "MATCHED" : "EXPECTATION_UNMET", ok ? null : "FETCH_NOT_USABLE", ok,
                    fetched instanceof FetchStreamResult.Fetched f2 ? f2.bytesTransferred() : 0L, null, null))
                throw new Abort(JobState.OUTCOME_UNKNOWN, "ATTEMPT_RECORD_FAILED");
            if (ok) meta = handle.finish();
        } catch (IOException e) {
            return HeavyEnd.failed("FAILED", "ARTEFACT_WRITE_FAILED", null);
        }
        if (fetched instanceof FetchStreamResult.Fetched f && f.bytesTransferred() > cap) {
            return HeavyEnd.failed("TOO_LARGE", "TOO_LARGE", "received over " + cap + " bytes");
        }
        if (meta == null) return HeavyEnd.failed("FAILED", "FETCH_FAILED", null);
        // Nothing is removed from the device until the two digests agree (BK-3).
        if (deviceDigest == null || !deviceDigest.equalsIgnoreCase(meta.plaintextSha256())) {
            return HeavyEnd.leftOnDevice("DIGEST_MISMATCH", "DIGEST_MISMATCH", "device and vault digests differ; work directory left");
        }
        String entry = "items/" + kind.id() + "/" + (vs == null ? "" : "vs" + vs + "/") + "bundle.tgz";
        return new HeavyEnd("COMPLETED", "COMPLETED", null, meta, entry, true);
    }

    /** The newest {@code hcp_report_*.tar.gz} of a C6 listing, by its listed time; null when none. */
    static String newestHcpReport(String listing) {
        String name = null;
        long newest = -1;
        for (String raw : listing.split("\\R")) {
            Matcher m = LS_ENTRY.matcher(raw.strip());
            if (!m.matches()) continue;
            String candidate = m.group(3);
            long mtime = Long.parseLong(m.group(2));
            if (CpSupportBundleHeavy.HCP_REPORT.matcher(candidate).matches() && mtime > newest) { newest = mtime; name = candidate; }
        }
        return name;
    }

    private static String firstHex64(String output) {
        if (output == null || output.isBlank()) return null;
        String first = output.strip().split("\\s+", 2)[0];
        return first.matches("[0-9a-fA-F]{64}") ? first : null;
    }

    /** C20 for exactly this item's directory and bundle, retried once; true when it ran clean. */
    private boolean removeWorkDir(Run run, String dir) {
        String command = CpSupportBundleHeavy.render(Step.REMOVE, dir, null, null);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Sent removed = sendStep(run, Step.REMOVE, command, 4096);
                if (removed.completed() && removed.exit() == 0) return true;
            } catch (Unknown unknown) {
                return false;
            }
        }
        return false;
    }

    /** Waits one poll interval; false when the thread was interrupted (a cancelled job), the flag is kept. */
    private boolean pause(Duration duration) {
        if (Thread.currentThread().isInterrupted()) return false;
        try {
            sleeper.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ------------------------------------------------------------------ shared

    private BackupArtefactManifestRecord manifest(Run run, ArtefactStore.ArtefactMetadata meta) {
        return new BackupArtefactManifestRecord(UUID.randomUUID().toString(), run.deviceId, Optional.empty(),
                ArtefactClass.SUPPORT_BUNDLE, VENDOR, Optional.of(run.version), fingerprint.of(run.hostSource),
                meta.plaintextSha256(), meta.plaintextBytes(), meta.ciphertextSha256(), meta.ciphertextBytes(), meta.keyId(),
                meta.wrappedDataKey(), ArtefactValidation.reachedWithoutRestore(ArtefactValidation.V1), "standard",
                Optional.of(run.expiresAt), meta.ref().value(), Optional.empty());
    }

    /** What one ledgered command answered: the transport result and, when it is not a usable answer, the closed failure code. */
    private record Sent(ExecResult result, String failure) {
        boolean completed() { return result instanceof ExecResult.Completed; }
        int exit() { return result instanceof ExecResult.Completed c ? c.exitStatus() : -1; }
        String output() { return result instanceof ExecResult.Completed c && c.output() != null ? c.output() : ""; }
        /** The command was sent and may have started something: a timeout or a lost channel is not proof it did nothing. */
        boolean uncertain() { return !completed(); }
    }

    /**
     * One command through the ledger: the pre-contact row and the boundary mark come first (a write is never sent without
     * them), the outcome row after. Never retried here.
     */
    private Sent send(Run run, GateResolution.Known gate, String command, String attemptClass, int maxBytes) {
        int step = run.step++;
        if (!attempts.findByJobAndStep(run.jobId, step).isEmpty()) throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
        String attempt = attempts.insertPreContact(run.jobId, run.epoch, step, gate.gateId(), attemptClass, 1);
        if (attempt == null || !attempts.markBoundaryCrossed(attempt, run.epoch))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "PRE_CONTACT_UNCERTAIN");
        ExecResult result = ssh.exec(run.session, new ExecSpec(command, false, 0, maxBytes), CpSupportBundle.timeout(gate));
        String failure = result instanceof ExecResult.Completed completed
                ? completed.output() == null ? "OUTPUT_UNAVAILABLE"
                : completed.output().getBytes(StandardCharsets.UTF_8).length > maxBytes ? "OUTPUT_LIMIT_EXCEEDED" : null
                : result instanceof ExecResult.TimedOut ? "TIMEOUT"
                : result instanceof ExecResult.ChannelFailed failed && "OUTPUT_LIMIT_EXCEEDED".equals(failed.reason())
                        ? "OUTPUT_LIMIT_EXCEEDED" : "CHANNEL_FAILED";
        long bytes = result instanceof ExecResult.Completed c && c.output() != null
                ? c.output().getBytes(StandardCharsets.UTF_8).length : 0L;
        if (!attempts.writeOutcome(attempt, run.epoch, failure == null ? "MATCHED" : "EXPECTATION_UNMET", failure,
                failure == null, bytes, null, null))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "ATTEMPT_RECORD_FAILED");
        return new Sent(result, failure);
    }

    /** One gated, ledgered read; a timeout, a channel failure or output over the bound is {@link Unknown}. */
    private ExecResult.Completed exec(Run run, Exec exec, int maxBytes) {
        GateResolution.Known gate = CpSupportBundle.gate(exec, gates, run.scope).orElseThrow(() -> new Unknown("GATE_NOT_KNOWN"));
        Sent sent = send(run, gate, exec.command(), "read", maxBytes);
        if (sent.failure() != null) throw new Unknown(sent.failure());
        return (ExecResult.Completed) sent.result();
    }

    private void finish(String jobId, long epoch, JobState state, String reason) {
        leases.transitionState(jobId, epoch, JobState.EXECUTING, state, ACTOR, "support_bundle_finished", reason);
    }

    /** Passes bytes on and remembers whether the first two are the gzip magic. */
    static final class GzipMagic extends OutputStream {
        private final OutputStream target;
        private int seen;
        private int first = -1, second = -1;

        GzipMagic(OutputStream target) { this.target = target; }

        @Override public void write(int b) throws IOException { write(new byte[] {(byte) b}, 0, 1); }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            for (int i = 0; i < len && seen < 2; i++, seen++) {
                if (seen == 0) first = b[off + i] & 0xff; else second = b[off + i] & 0xff;
            }
            target.write(b, off, len);
        }

        boolean ok() { return first == 0x1f && second == 0x8b; }
    }
}
