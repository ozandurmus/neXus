package com.securityexpert.nexus.ui2.worker.failover;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.securityexpert.nexus.ui2.capability.AmbiguousGateResolutionException;
import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.capability.GateResolver;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.failover.FgtFailoverReadinessSwitch;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiGatePlan;
import com.securityexpert.nexus.ui2.worker.failover.FgtFailoverChecks.Observation;
import com.securityexpert.nexus.ui2.worker.failover.FgtFailoverChecks.Result;
import com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver;

/**
 * FortiGate (FGCP) failover readiness (docs/design/PO_DECISION_RECORD_2026_11_17_FGT_FAILOVER_READINESS_READS.md):
 * READINESS ONLY. No write path, no write gate and no state-changing command exists here ("config global" and "end" only
 * move between contexts). A run of any other kind, or a worker whose switch is off, ends UNKNOWN before any device contact.
 *
 * <p>One trusted SSH session on the enrolled cluster address (it lands on the primary; the secondary has no address of its
 * own, design question Q2): {@code get system status}, then in the global context when VDOMs are on the five reads one at a
 * time through their gate rows. A gate that does not resolve KNOWN sends nothing and leaves its checks UNKNOWN.
 */
public final class FgtFailoverJobExecutor {
    private static final System.Logger LOG = System.getLogger(FgtFailoverJobExecutor.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACTOR = "system:fgt-failover-worker";
    static final String READINESS_KIND = "READINESS";
    static final String VENDOR = "fortinet";
    /** The unit of an enrolled single-address FGCP cluster is named by its device: see CpFailoverService. */
    public static final String SINGLE_PREFIX = "fgt-single|";
    private static final Duration CONNECT = Duration.ofSeconds(30);
    private static final Duration READ_CAP = Duration.ofSeconds(30);
    private static final int MAX_OUTPUT_CHARS = 262144;
    /** The inventory's own bound on VDOMs per unit; the rest are counted as unread, never skipped silently. */
    private static final int MAX_VDOMS = 64;
    private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    @FunctionalInterface public interface Pause { void sleep(Duration duration) throws InterruptedException; }

    /** The nine reads: gate id, shell context of the gate row, literal, shape-log line cap. */
    enum Read {
        STATUS("fgt_get_system_status", "top_or_global", "get system status", 40),
        HA_STATUS("fgt_get_system_ha_status", "cli", "get system ha status", 120),
        HA_CONFIG("fgt_show_full_configuration_system_ha", "top_or_global", "show full-configuration system ha", 80),
        CHECKSUM("fgt_failover_checksum_cluster", "top_or_global", "diagnose sys ha checksum cluster", 40),
        DUMP("fgt_failover_dump_by_group", "top_or_global", "diagnose sys ha dump-by group", 60),
        FLAG("fgt_failover_ha_failover_status", "top_or_global", "execute ha failover status", 10),
        /** Information check 10 (traffic and ARP). The two literals live here and in V248 (DRAFTED until the PO approves). */
        PERFORMANCE("fgt_failover_performance_status", "top_or_global", "get system performance status", 20),
        ARP("fgt_failover_arp", "top_or_global", "get system arp", 10),
        /**
         * The already approved row that lists the VDOMs (the inventory's own read, fgt_show_system_interface, global context): a
         * multi-VDOM unit's ARP is read inside each VDOM it names. Sent only when the ARP read is approved and VDOMs are on.
         */
        INTERFACES("fgt_show_system_interface", "global", "show system interface", 30);
        final String gateId, shell, command; final int maxShapeLines;
        Read(String gateId, String shell, String command, int maxShapeLines) {
            this.gateId = gateId; this.shell = shell; this.command = command; this.maxShapeLines = maxShapeLines;
        }
    }
    private static final String MOVES_KEY = "config global | config vdom | edit <vdom> | end";
    private static final class Stop extends RuntimeException {
        final String code;
        Stop(String code) { super(code); this.code = code; }
    }

    private final JooqCpFailoverRepository store;
    private final DeviceRepository devices;
    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DeviceTransport ssh;
    private final GateRegistryPort gates;
    private final DevicePlatformFactsRepository platformFacts;
    private final FgtFailoverReadinessSwitch readinessSwitch;
    private final Pause pause;
    private final Duration commandPause;
    private String jobId, runId, deviceId;
    private long epoch;
    private int commandIndex, sent;
    private final java.util.Set<Read> shapeLogged = new java.util.HashSet<>();

    public FgtFailoverJobExecutor(JooqCpFailoverRepository store, DeviceRepository devices, JobLeaseRepository leases,
            JobStepAttemptRepository attempts, DeviceTransport ssh, GateRegistryPort gates,
            DevicePlatformFactsRepository platformFacts, FgtFailoverReadinessSwitch readinessSwitch, Duration commandPause) {
        this(store, devices, leases, attempts, ssh, gates, platformFacts, readinessSwitch, d -> Thread.sleep(d.toMillis()), commandPause);
    }

    FgtFailoverJobExecutor(JooqCpFailoverRepository store, DeviceRepository devices, JobLeaseRepository leases,
            JobStepAttemptRepository attempts, DeviceTransport ssh, GateRegistryPort gates,
            DevicePlatformFactsRepository platformFacts, FgtFailoverReadinessSwitch readinessSwitch, Pause pause,
            Duration commandPause) {
        this.store = store; this.devices = devices; this.leases = leases; this.attempts = attempts; this.ssh = ssh;
        this.gates = java.util.Objects.requireNonNull(gates);
        this.platformFacts = java.util.Objects.requireNonNull(platformFacts);
        this.readinessSwitch = java.util.Objects.requireNonNull(readinessSwitch);
        this.pause = pause; this.commandPause = commandPause;
    }

    public void execute(String jobId, long epoch) {
        var found = store.runByJob(jobId);
        if (found.isEmpty() || !VENDOR.equals(found.get().vendor())) {
            leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.FAILED, ACTOR, "fgt_failover_missing_run", "RUN_NOT_FOUND");
            return;
        }
        var run = found.get();
        this.jobId = jobId; this.runId = run.id(); this.epoch = epoch;
        this.commandIndex = 0; this.sent = 0; this.shapeLogged.clear();
        if (!leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR, "fgt_failover_start")) return;
        try {
            if (!READINESS_KIND.equals(run.kind())) {
                store.state(runId, "STOPPED", "STOPPED", "UNKNOWN", null, "READINESS_ONLY");
                leases.transitionState(jobId, epoch, JobState.EXECUTING, JobState.FAILED, ACTOR, "fgt_failover_refused", "READINESS_ONLY");
                return;
            }
            if (!readinessSwitch.enabled()) {
                finish("UNKNOWN", null, "FGT_READINESS_SWITCH_OFF");
                return;
            }
            String ref = run.clusterRef();
            if (ref == null || !ref.startsWith(SINGLE_PREFIX) || !DEVICE_ID.matcher(ref.substring(SINGLE_PREFIX.length())).matches())
                throw new Stop("CLUSTER_NOT_ELIGIBLE");
            this.deviceId = ref.substring(SINGLE_PREFIX.length());
            var device = devices.find(deviceId).filter(d -> d.permitsReadCollection() && VENDOR.equals(d.vendorHint()))
                .orElseThrow(() -> new Stop("DEVICE_NOT_ELIGIBLE"));
            if (!attempts.findByJobAndStep(jobId, 0).isEmpty()) throw new Stop("PRIOR_ATTEMPT_NOT_REPLAYED");
            Map<Read, GateResolution.Known> approved = new LinkedHashMap<>();
            Map<Read, String> refused = new LinkedHashMap<>();
            for (Read read : Read.values()) {
                String state = resolve(new CanonicalCommandKey(VENDOR, "fortigate", read.shell, "SSH_EXEC", read.command), read.gateId, approved, read);
                if (state != null) refused.put(read, state);
            }
            boolean movesOk = resolveMoves();
            store.state(runId, "PRECHECK", "PRECHECK", null, null, null);
            Observation observation = approved.isEmpty()
                ? new Observation(null, null, null, null, null, Optional.empty(), Optional.empty(), false)
                : collect(device.credentialReferenceId(), approved, movesOk);
            List<Result> evaluated = new ArrayList<>(FgtFailoverChecks.evaluate(observation,
                platformFacts.find(deviceId).flatMap(f -> f.serialNumber()).map(String::strip).filter(s -> !s.isEmpty())));
            // Check 10 (information): the primary's reading against the unit's earlier readiness run.
            TrafficArpCheck.Reading reading = observation.traffic() == null ? TrafficArpCheck.Reading.NONE : observation.traffic();
            TrafficArpCheck.Outcome traffic = TrafficArpCheck.evaluate(reading,
                reading.trafficBytesPerSecond() == null && reading.arpEntries() == null ? Optional.empty()
                    : TrafficArpCheck.previous(store, runId, FgtFailoverChecks.TRAFFIC_ARP));
            evaluated.add(new Result(FgtFailoverChecks.TRAFFIC_ARP, traffic.status(), traffic.reason(), traffic.evidence()));
            List<Result> results = withGateReasons(evaluated, refused, reading.trafficScope() != null);
            for (Result result : results) store.check(runId, "pre", deviceId, null, result.check(), result.status(), derived(withEnrolledRole(result)));
            String outcome = FgtFailoverChecks.outcome(results);
            Result lead = results.stream().filter(Result::blocking).filter(r -> "FAIL".equals(r.status())).findFirst()
                .or(() -> results.stream().filter(Result::blocking).filter(r -> !"PASS".equals(r.status())).findFirst()).orElse(null);
            finish(outcome, lead == null ? null : lead.check(), lead == null ? "ALL_CHECKS_PASSED" : lead.reason());
        } catch (Stop stopped) {
            finish("UNKNOWN", null, stopped.code);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finish("UNKNOWN", null, "INTERRUPTED");
        } catch (RuntimeException unexpected) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(unexpected);
            finish("UNKNOWN", null, "COLLECTION_FAILED");
        }
    }

    /** Null when the gate resolved KNOWN (and is recorded); otherwise the reason it was not sent. */
    private String resolve(CanonicalCommandKey key, String gateId, Map<Read, GateResolution.Known> approved, Read read) {
        try {
            var resolved = GateResolver.resolve(key, Optional.of(ActionClass.CLASS_0_READ), gates);
            if (resolved instanceof GateResolution.Known known && gateId.equals(known.gateId())) { approved.put(read, known); return null; }
            if (resolved instanceof GateResolution.Unknown unknown && unknown.reason().contains("DRAFTED")) return "GATE_NOT_SIGNED_OFF";
            return "GATE_UNAVAILABLE";
        } catch (AmbiguousGateResolutionException ambiguous) {
            return "GATE_UNAVAILABLE";
        }
    }

    private boolean resolveMoves() {
        try {
            var resolved = GateResolver.resolve(new CanonicalCommandKey(VENDOR, "fortigate", "context", "SSH_EXEC", MOVES_KEY),
                Optional.of(ActionClass.CLASS_0_READ), gates);
            return resolved instanceof GateResolution.Known known && "fgt_context_moves".equals(known.gateId());
        } catch (AmbiguousGateResolutionException ambiguous) {
            return false;
        }
    }

    /** A check made UNKNOWN by a read that was never sent says why. */
    private static List<Result> withGateReasons(List<Result> results, Map<Read, String> refused, boolean multiVdom) {
        if (refused.isEmpty()) return results;
        List<Result> out = new ArrayList<>();
        for (Result r : results) {
            List<Read> needs = switch (r.check()) {
                case 1, 2, 3 -> List.of(Read.HA_STATUS);
                case 4 -> List.of(Read.HA_STATUS, Read.CHECKSUM);
                case 5 -> List.of(Read.HA_CONFIG);
                case 8 -> List.of(Read.HA_CONFIG, Read.DUMP, Read.FLAG);
                case 9 -> List.of(Read.HA_STATUS, Read.STATUS);
                case FgtFailoverChecks.TRAFFIC_ARP -> multiVdom ? List.of(Read.PERFORMANCE, Read.ARP, Read.INTERFACES) : List.of(Read.PERFORMANCE, Read.ARP);
                default -> List.<Read>of();
            };
            Optional<String> reason = needs.stream().map(refused::get).filter(java.util.Objects::nonNull).findFirst();
            out.add(reason.isPresent() && "UNKNOWN".equals(r.status()) ? new Result(r.check(), r.status(), reason.get(), r.evidence()) : r);
        }
        return out;
    }

    /**
     * Check 1 PASS means the status lists one primary and one secondary and the enrolled unit is the primary (identity MATCH):
     * the enrolled device is recorded as the active member, as the primary reports. The secondary has no device record of its
     * own (it has no address; design question Q2), so no row is written for it and no identity is invented.
     */
    static Result withEnrolledRole(Result result) {
        if (result.check() != 1 || !"PASS".equals(result.status()) || !"MATCH".equals(result.evidence().get("identity"))) return result;
        Map<String, Object> evidence = new LinkedHashMap<>(result.evidence());
        evidence.put("role", "ACTIVE");
        return new Result(result.check(), result.status(), result.reason(), evidence);
    }

    private static String derived(Result result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", result.reason());
        body.putAll(result.evidence());
        try { return JSON.writeValueAsString(body); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Derived projection unavailable"); }
    }

    private void finish(String outcome, Integer check, String code) {
        store.state(runId, "DONE", "DONE", outcome, check == null ? null : checkName(check), code);
        leases.transitionState(jobId, epoch, JobState.EXECUTING, JobState.COMPLETED, ACTOR, "fgt_readiness_done", outcome);
        com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.recordReadinessRun(outcome);
    }
    private static String checkName(int check) {
        return switch (check) {
            case 1 -> "HA mode and members"; case 2 -> "Heartbeat links"; case 3 -> "Monitored interfaces";
            case 4 -> "Configuration sync"; case 5 -> "Session pickup"; case 6 -> "Sessions carried";
            case 7 -> "Version parity"; case 8 -> "Switch preconditions"; case 9 -> "Last failover";
            case FgtFailoverChecks.TRAFFIC_ARP -> "Traffic and ARP"; default -> "Pre-check";
        };
    }

    // ---- the session -------------------------------------------------------------------------------------------

    private Observation collect(String credentialRef, Map<Read, GateResolution.Known> approved, boolean movesOk)
            throws InterruptedException {
        var endpoint = devices.findEndpointByDeviceId(deviceId)
            .filter(e -> deviceId.equals(e.deviceId()) && "ssh_exec".equalsIgnoreCase(e.transportKind()))
            .orElseThrow(() -> new Stop("ENDPOINT_MISSING"));
        String host = com.securityexpert.nexus.ui2.persistence.runtime.EndpointAddress.host(endpoint.addressRef());
        int port = com.securityexpert.nexus.ui2.persistence.runtime.EndpointAddress.port(endpoint.addressRef(), 22);
        var connected = ssh.connect(new ConnectionTarget(endpoint.endpointId(), host, port),
            new ConnectSpec(credentialRef, PersistedManagementEndpointTrustResolver.scopeRef(host, port), Optional.empty()), CONNECT);
        if (!(connected instanceof ConnectResult.Authenticated auth)) throw new Stop("TRUSTED_CONNECTION_REQUIRED");
        TransportSession session = auth.session();
        boolean moved = false;
        try {
            Optional<String> status = Optional.empty();
            boolean multiVdom = false;
            if (approved.containsKey(Read.STATUS)) {
                status = read(session, approved.get(Read.STATUS), Read.STATUS).filter(o -> o.contains("Version:"));
                if (status.isEmpty() && movesOk) {
                    // The approved retry of this row: once inside the global context when refused at the top level.
                    move(session, FortiGatePlan.CONFIG_GLOBAL);
                    status = read(session, approved.get(Read.STATUS), Read.STATUS).filter(o -> o.contains("Version:"));
                    move(session, FortiGatePlan.END);
                    multiVdom = status.isPresent();
                }
            }
            var parsedStatus = FortiGatePlan.parseStatus(status.orElse(null));
            multiVdom = multiVdom || parsedStatus.multiVdom();
            boolean enterGlobal = multiVdom;
            if (enterGlobal && !movesOk) {
                // VDOMs are on and the context moves are not approved: the global reads cannot be asked.
                return observation(null, null, null, null, null, status, parsedStatus, null);
            }
            if (enterGlobal) { move(session, FortiGatePlan.CONFIG_GLOBAL); moved = true; }
            Optional<String> ha = approved.containsKey(Read.HA_STATUS) ? read(session, approved.get(Read.HA_STATUS), Read.HA_STATUS) : Optional.empty();
            Optional<String> config = approved.containsKey(Read.HA_CONFIG) ? read(session, approved.get(Read.HA_CONFIG), Read.HA_CONFIG) : Optional.empty();
            Optional<String> sums = approved.containsKey(Read.CHECKSUM) ? read(session, approved.get(Read.CHECKSUM), Read.CHECKSUM) : Optional.empty();
            Optional<String> dump = approved.containsKey(Read.DUMP) ? read(session, approved.get(Read.DUMP), Read.DUMP) : Optional.empty();
            Optional<String> flag = approved.containsKey(Read.FLAG) ? read(session, approved.get(Read.FLAG), Read.FLAG) : Optional.empty();
            Optional<String> performance = approved.containsKey(Read.PERFORMANCE) ? read(session, approved.get(Read.PERFORMANCE), Read.PERFORMANCE) : Optional.empty();
            var performanceParsed = FgtFailoverChecks.parsePerformance(performance.orElse(null));
            logShape(Read.PERFORMANCE, performance, approved.containsKey(Read.PERFORMANCE) && performanceParsed.bytesPerSecond() == null);
            Integer arpEntries = null;
            Map<String, Integer> arpByVdom = null;
            if (!enterGlobal) {
                Optional<String> arp = approved.containsKey(Read.ARP) ? read(session, approved.get(Read.ARP), Read.ARP) : Optional.empty();
                arpEntries = FgtFailoverChecks.arpEntries(arp.orElse(null));
                logShape(Read.ARP, arp, approved.containsKey(Read.ARP) && arpEntries == null);
            } else if (approved.containsKey(Read.ARP) && approved.containsKey(Read.INTERFACES)) {
                // Multi-VDOM: list the VDOMs from the global context, leave it, then read the ARP table inside each VDOM.
                Optional<String> listing = read(session, approved.get(Read.INTERFACES), Read.INTERFACES);
                var vdoms = vdomNames(listing.orElse(null));
                logShape(Read.INTERFACES, listing, vdoms.isEmpty());
                move(session, FortiGatePlan.END);
                moved = false;
                arpByVdom = new java.util.LinkedHashMap<>();
                int ordinal = 0;
                for (String vdom : vdoms) {
                    String label = String.valueOf(++ordinal);
                    arpByVdom.put(label, ordinal > MAX_VDOMS ? null : vdomArp(session, approved.get(Read.ARP), vdom));
                }
                int sum = 0;
                boolean complete = !arpByVdom.isEmpty();
                for (Integer count : arpByVdom.values()) { if (count == null) complete = false; else sum += count; }
                arpEntries = complete ? sum : null;
            }
            var reading = new TrafficArpCheck.Reading(performanceParsed.bytesPerSecond(), arpEntries, performanceParsed.averageSessions(),
                arpByVdom, enterGlobal ? "BOX" : null);
            var haParsed = FgtFailoverChecks.parseHaStatus(ha.orElse(null));
            var configParsed = FgtFailoverChecks.parseHaConfig(config.orElse(null));
            var sumsParsed = FgtFailoverChecks.parseChecksums(sums.orElse(null));
            var dumpParsed = FgtFailoverChecks.parseUptimes(dump.orElse(null));
            var flagParsed = FgtFailoverChecks.parseFlag(flag.orElse(null));
            logShape(Read.STATUS, status, !parsedStatus.model().isPresent());
            logShape(Read.HA_STATUS, ha, !haParsed.recognised());
            logShape(Read.HA_CONFIG, config, !configParsed.recognised());
            logShape(Read.CHECKSUM, sums, !sumsParsed.recognised());
            logShape(Read.DUMP, dump, !dumpParsed.recognised());
            logShape(Read.FLAG, flag, flagParsed == FgtFailoverChecks.Flag.UNRECOGNISED);
            return observation(ha.isPresent() ? haParsed : null, config.isPresent() ? configParsed : null,
                sums.isPresent() ? sumsParsed : null, dump.isPresent() ? dumpParsed : null,
                flag.isPresent() ? flagParsed : null, status, parsedStatus, reading);
        } finally {
            try { if (moved) move(session, FortiGatePlan.END); }
            finally { ssh.disconnect(session); }
        }
    }

    private static Observation observation(FgtFailoverChecks.HaStatus ha, FgtFailoverChecks.HaConfig config,
            FgtFailoverChecks.Checksums sums, FgtFailoverChecks.Uptimes dump, FgtFailoverChecks.Flag flag,
            Optional<String> status, FortiGatePlan.Status parsed, TrafficArpCheck.Reading traffic) {
        return new Observation(ha, config, sums, dump, flag, FgtFailoverChecks.systemTime(status.orElse(null)),
            parsed.serial(), parsed.version().isPresent(), traffic);
    }

    /**
     * The VDOM names of {@code show system interface} (the inventory's own enumeration), sorted so that the ordinal given to a
     * VDOM does not depend on the order the device printed its interfaces. A name outside FortiOS's own rules
     * ({@link FortiGatePlan#validVdom}) can not be put in "edit": it stays in the list and is counted as a VDOM that
     * could not be read, so the count of VDOMs stays true.
     */
    private static java.util.List<String> vdomNames(String listing) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (FortiGatePlan.Iface iface : FortiGatePlan.parseInterfaces(listing)) names.add(iface.vdom());
        return new ArrayList<>(names);
    }

    /** One VDOM's ARP count: "config vdom", "edit <vdom>", the read, "end". Null (unread) when a move or the read fails. */
    private Integer vdomArp(TransportSession session, GateResolution.Known gate, String vdom) throws InterruptedException {
        if (!FortiGatePlan.validVdom(vdom)) return null;
        boolean entered = false;
        try {
            entered = moveChecked(session, FortiGatePlan.CONFIG_VDOM) && moveChecked(session, FortiGatePlan.editVdom(vdom));
            if (!entered) return null;
            Optional<String> arp = read(session, gate, Read.ARP);
            Integer count = FgtFailoverChecks.arpEntries(arp.orElse(null));
            logShape(Read.ARP, arp, count == null);
            return count;
        } finally {
            move(session, FortiGatePlan.END);
        }
    }

    /** A context move whose refusal is known: false when the shell answered with an error. */
    private boolean moveChecked(TransportSession session, String command) {
        ExecResult result = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.withoutRecording(
            () -> ssh.execInteractive(session, new ExecSpec(command, true), Duration.ofSeconds(30)));
        // A context move normally answers only with the new prompt, or with nothing at all: empty output is success. The SSH
        // transport reports a prompt that came back with no text as ChannelFailed("empty output") (seen live 2026-11-17).
        if (result instanceof ExecResult.ChannelFailed failed && "empty output".equals(failed.reason())) return true;
        return result instanceof ExecResult.Completed completed
            && (completed.output() == null || completed.output().isBlank() || !FortiGatePlan.isCliError(completed.output()));
    }

    /** A context move ("config global", "end"): the answer is the new prompt; it is never a read and never recorded. */
    private void move(TransportSession session, String command) {
        com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.withoutRecording(
            () -> ssh.execInteractive(session, new ExecSpec(command, true), Duration.ofSeconds(30)));
    }

    /** One approved read: a pause, the attempt ledger, the send (never recorded in the job transcript), no retry. */
    private Optional<String> read(TransportSession session, GateResolution.Known gate, Read read) throws InterruptedException {
        if (sent++ > 0 && !commandPause.isZero()) pause.sleep(commandPause);
        if (!attempts.findByJobAndStep(jobId, commandIndex).isEmpty()) throw new Stop("PRIOR_ATTEMPT_NOT_REPLAYED");
        String attempt = attempts.insertPreContact(jobId, epoch, commandIndex++, gate.gateId(), gate.actionClass().id(), 1);
        if (attempt == null) throw new Stop("PRE_CONTACT_RECORD_FAILED");
        if (!attempts.markBoundaryCrossed(attempt, epoch)) throw new Stop("PRE_CONTACT_UNCERTAIN");
        store.command(runId, gate.gateId());
        Duration timeout = read != Read.INTERFACES && Duration.ofSeconds(gate.timeoutS()).compareTo(READ_CAP) > 0 ? READ_CAP : Duration.ofSeconds(gate.timeoutS());
        ExecResult result = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.withoutRecording(
            () -> ssh.execInteractive(session, new ExecSpec(read.command, true), timeout));
        if (!(result instanceof ExecResult.Completed completed) || FortiGatePlan.isCliError(completed.output())
                || completed.output() == null || completed.output().length() > MAX_OUTPUT_CHARS) {
            attempts.writeOutcome(attempt, epoch, "FAILED", "COMMAND_UNAVAILABLE", false, null, null, null);
            return Optional.empty();
        }
        if (!attempts.writeOutcome(attempt, epoch, "MATCHED", null, true, null, null, null))
            throw new Stop("ATTEMPT_RECORD_FAILED");
        return Optional.of(stripEcho(completed.output(), read.command));
    }

    /** The shell echoes the command on the first line and ends with the prompt: neither is device output. */
    static String stripEcho(String out, String command) {
        String text = out;
        int nl = text.indexOf('\n');
        if (nl >= 0 && text.substring(0, nl).strip().equals(command)) text = text.substring(nl + 1);
        int last = text.lastIndexOf('\n');
        if (last >= 0 && text.substring(last + 1).strip().endsWith("#")) text = text.substring(0, last + 1);
        return text;
    }

    private void logShape(Read read, Optional<String> answer, boolean unparsed) {
        if (!unparsed || !shapeLogged.add(read)) return;
        String shape = answer.isEmpty() ? "[no answer]" : String.valueOf(FgtShape.shape(answer.get(), read.maxShapeLines));
        LOG.log(System.Logger.Level.INFO, "[READINESS_SHAPE] vendor=fortinet read={0} shape={1}", read.gateId, shape);
    }
}
