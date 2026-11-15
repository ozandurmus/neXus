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
    private final ObjectMapper json = new ObjectMapper();

    public CpSupportBundleJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceTransport ssh, GateRegistryPort gates, SupportBundleRunStore store, ArtefactStore artefacts,
            HostnameFingerprint fingerprint) {
        this(leases, attempts, devices, ssh, gates, store, artefacts, fingerprint, Clock.systemUTC());
    }

    CpSupportBundleJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts, DeviceRepository devices,
            DeviceTransport ssh, GateRegistryPort gates, SupportBundleRunStore store, ArtefactStore artefacts,
            HostnameFingerprint fingerprint, Clock clock) {
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
        boolean gatesKnown = CpSupportBundle.sftpGate(gates).isPresent();
        for (Exec e : Exec.values()) gatesKnown &= CpSupportBundle.gate(e, gates).isPresent();
        if (request.isEmpty() || device.isEmpty() || !device.get().permitsReadCollection()
                || !VENDOR.equals(device.get().vendorHint()) || !"gateway".equals(device.get().role())
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
            int stored = 0, completed = 0;
            for (ItemRow item : items) {
                store.markItemRunning(item.id());
                run.gateIds.clear();
                String result;
                try {
                    result = switch (SupportBundleKind.fromId(item.kind()).orElseThrow()) {
                        case CPDATA_COLLECTOR_PRESENT -> cpdataPresent(run, item);
                        case CP_GAIA_EXTRAS -> gaiaExtras(run, item);
                        case CP_HCP_LATEST -> hcpLatest(run, item, req);
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
            finish(jobId, epoch, abort.state, abort.code);
        } catch (Exception failure) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(failure);
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
                if (order[i] == Exec.CPU_MULTI) run.precheck.put("cpu_max_core_pct", maxCorePercent(text));
                if (order[i] == Exec.DF_VAR_LOG) run.precheck.put("disk_free_bytes",
                        com.securityexpert.nexus.ui2.worker.backup.BackupCapabilityExecutor.parseDfAvailableBytes(text).orElse(null));
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

    // ------------------------------------------------------------------ shared

    private BackupArtefactManifestRecord manifest(Run run, ArtefactStore.ArtefactMetadata meta) {
        return new BackupArtefactManifestRecord(UUID.randomUUID().toString(), run.deviceId, Optional.empty(),
                ArtefactClass.SUPPORT_BUNDLE, VENDOR, Optional.of(run.version), fingerprint.of(run.hostSource),
                meta.plaintextSha256(), meta.plaintextBytes(), meta.ciphertextSha256(), meta.ciphertextBytes(), meta.keyId(),
                meta.wrappedDataKey(), ArtefactValidation.reachedWithoutRestore(ArtefactValidation.V1), "standard",
                Optional.of(run.expiresAt), meta.ref().value(), Optional.empty());
    }

    /** One gated, ledgered read; a timeout, a channel failure or output over the bound is {@link Unknown}. */
    private ExecResult.Completed exec(Run run, Exec exec, int maxBytes) {
        GateResolution.Known gate = CpSupportBundle.gate(exec, gates).orElseThrow(() -> new Unknown("GATE_NOT_KNOWN"));
        int step = run.step++;
        if (!attempts.findByJobAndStep(run.jobId, step).isEmpty()) throw new Abort(JobState.OUTCOME_UNKNOWN, "PRIOR_ATTEMPT_NOT_REPLAYED");
        String attempt = attempts.insertPreContact(run.jobId, run.epoch, step, gate.gateId(), "read", 1);
        if (attempt == null || !attempts.markBoundaryCrossed(attempt, run.epoch))
            throw new Abort(JobState.OUTCOME_UNKNOWN, "PRE_CONTACT_UNCERTAIN");
        ExecResult result = ssh.exec(run.session, new ExecSpec(exec.command(), false, 0, maxBytes), CpSupportBundle.timeout(gate));
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
        if (failure != null) throw new Unknown(failure);
        return (ExecResult.Completed) result;
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
