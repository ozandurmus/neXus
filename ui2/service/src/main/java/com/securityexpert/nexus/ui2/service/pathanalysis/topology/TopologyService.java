package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * Stage A3: holds the current in-memory topology, rebuilds it on request and manages overrides (D-1, D-8).
 *
 * <p>A rebuild is synchronous and bounded: one at a time, refused above fixed input sizes, reads stored inventory
 * only -- it never submits a job and never contacts a device. A read never builds: when no graph is held (for
 * example after a service restart) the read says so instead of building one. {@link TopologyAutoRebuild} (startup and
 * after an inventory change, as the system actor) calls the same {@link #rebuild} under the same lock.</p>
 */
public final class TopologyService {

    /** Refusal with a stable code; the controller maps it to a status. */
    public static final class Refusal extends RuntimeException {
        private final String code;
        private final int status;

        public Refusal(String code, int status) {
            super(code, null, false, false);
            this.code = code;
            this.status = status;
        }

        public String code() {
            return code;
        }

        public int status() {
            return status;
        }
    }

    public static final int MAX_DEVICES = 2_000;
    /** Interfaces + addresses + routes over every input run. */
    public static final int MAX_INPUT_ROWS = 2_000_000;
    public static final int MAX_STORED_CONFLICTS = 5_000;
    public static final int SNAPSHOTS_KEPT = 20;

    /** One rebuild result: the graph and the header that was persisted for it. */
    public record Rebuilt(TopologyGraph graph, TopologyRepository.SnapshotHeader header) {
    }

    /** An override as the API reports it: the row plus its status against the current graph. */
    public record OverrideView(TopologyLinkOverride override, String status, Optional<String> reason) {
    }

    /** What an operator may submit; validated before anything is written. */
    public record OverrideRequest(String action, String linkKind, String aDeviceId, String aContext, String aInterfaceName,
            String bDeviceId, String bContext, String bInterfaceName, Optional<String> assumedSubnet, String reasonCode,
            Optional<String> note) {
    }

    private final DeviceRepository devices;
    private final DeviceInventoryRepository inventory;
    private final TopologyRepository repository;
    private final Clock clock;
    private final ManagementVsSource managementVs;
    private final SecureRandom random = new SecureRandom();
    private final ReentrantLock rebuildLock = new ReentrantLock();
    private final AtomicReference<TopologyGraph> current = new AtomicReference<>();

    public TopologyService(DeviceRepository devices, DeviceInventoryRepository inventory, TopologyRepository repository,
            Clock clock) {
        this(devices, inventory, repository, clock, ManagementVsSource.NONE);
    }

    public TopologyService(DeviceRepository devices, DeviceInventoryRepository inventory, TopologyRepository repository,
            Clock clock, ManagementVsSource managementVs) {
        this.managementVs = Objects.requireNonNull(managementVs, "managementVs");
        this.devices = Objects.requireNonNull(devices, "devices");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<TopologyGraph> current() {
        return Optional.ofNullable(current.get());
    }

    public Optional<TopologyRepository.SnapshotHeader> latestSnapshot() {
        return repository.latestSnapshot();
    }

    public Rebuilt rebuild(String actorFingerprint) {
        Objects.requireNonNull(actorFingerprint, "actorFingerprint");
        if (!rebuildLock.tryLock()) {
            throw new Refusal("TOPOLOGY_REBUILD_IN_PROGRESS", 409);
        }
        try {
            long started = System.nanoTime();
            List<DeviceSummaryRecord> candidates = candidates();
            Map<String, InventoryRun> runs = inventory.findLatestRuns(candidates.stream()
                    .map(DeviceSummaryRecord::deviceId).toList()).stream()
                    .collect(Collectors.toMap(InventoryRun::deviceId, Function.identity(), (a, b) -> a));
            long rows = runs.values().stream().flatMap(r -> r.contexts().stream())
                    .mapToLong(c -> c.routes().size() + c.interfaces().size()
                            + c.interfaces().stream().mapToLong(i -> i.addresses().size()).sum())
                    .sum();
            if (rows > MAX_INPUT_ROWS) {
                throw new Refusal("TOPOLOGY_INPUT_TOO_LARGE", 422);
            }
            List<TopologyInput.DeviceFacts> facts = new ArrayList<>();
            for (DeviceSummaryRecord d : candidates) {
                facts.add(new TopologyInput.DeviceFacts(d.deviceId(), d.vendorHint(), d.observedHostname(),
                        d.clusterMemberRef(), Optional.ofNullable(runs.get(d.deviceId()))));
            }
            byte[] salt = new byte[32];
            random.nextBytes(salt);
            Instant builtAt = clock.instant();
            String snapshotId = "tps-" + UUID.randomUUID();
            TopologyGraph graph = TopologyBuilder.build(new TopologyInput(snapshotId, builtAt, facts,
                    repository.listOverrides(), salt, loadManagementVs(builtAt)));
            long millis = (System.nanoTime() - started) / 1_000_000;
            var header = new TopologyRepository.SnapshotHeader(snapshotId, builtAt, actorFingerprint,
                    graph.fingerprints(), graph.counts(), 0, millis);
            int stored = repository.recordSnapshot(header, graph.conflicts(), MAX_STORED_CONFLICTS, SNAPSHOTS_KEPT);
            current.set(graph);
            return new Rebuilt(graph, new TopologyRepository.SnapshotHeader(snapshotId, builtAt, actorFingerprint,
                    graph.fingerprints(), graph.counts(), stored, millis));
        } finally {
            rebuildLock.unlock();
        }
    }

    /** A failing management read must never fail a rebuild: no facts means every VIP prefix stays UNKNOWN. */
    private List<TopologyInput.ManagementVs> loadManagementVs(Instant builtAt) {
        try {
            return managementVs.load(builtAt);
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    /** The devices a topology is built from; refused above {@link #MAX_DEVICES}. */
    private List<DeviceSummaryRecord> candidates() {
        List<DeviceSummaryRecord> candidates = devices.listAll().stream()
                .filter(d -> TopologyInput.VENDOR_CHECK_POINT.equals(d.vendorHint())
                        || TopologyInput.VENDOR_PALO_ALTO.equals(d.vendorHint()))
                .filter(d -> "gateway".equals(d.role()))
                .filter(d -> d.enrollmentState() != DeviceEnrollmentState.DRAFT)
                .toList();
        if (candidates.size() > MAX_DEVICES) {
            throw new Refusal("TOPOLOGY_INPUT_TOO_LARGE", 422);
        }
        return candidates;
    }

    /**
     * The inventory fingerprint the stored inventory would produce right now, computed from run identities only (no
     * interface, address or route row is read) and equal to {@code graph.fingerprints().inventory()} of a build over
     * the same inventory. Read only; never contacts a device.
     */
    public String liveInventoryFingerprint() {
        List<DeviceSummaryRecord> candidates = candidates();
        Map<String, DeviceInventoryRepository.RunStamp> stamps = inventory.findLatestRunStamps(candidates.stream()
                .map(DeviceSummaryRecord::deviceId).toList()).stream()
                .collect(Collectors.toMap(DeviceInventoryRepository.RunStamp::deviceId, Function.identity(), (a, b) -> a));
        List<String> lines = new ArrayList<>();
        for (DeviceSummaryRecord d : candidates) {
            var stamp = Optional.ofNullable(stamps.get(d.deviceId()));
            lines.add(TopologyBuilder.inventoryLine(d.deviceId(), stamp.map(DeviceInventoryRepository.RunStamp::runId),
                    stamp.map(DeviceInventoryRepository.RunStamp::collectedAt), d.clusterMemberRef()));
        }
        return TopologyBuilder.inventoryFingerprint(lines);
    }

    /**
     * Receives the "a topology read happened" signal (see {@link #requestRefreshCheck()}). Implemented by the
     * automatic rebuild; absent in a plain service, where the signal does nothing.
     */
    public interface RefreshTrigger {
        void topologyRead();
    }

    private volatile RefreshTrigger refreshTrigger;

    /** Wires the automatic rebuild; called once at composition. */
    public void attachRefreshTrigger(RefreshTrigger trigger) {
        this.refreshTrigger = trigger;
    }

    /**
     * Called by topology and path reads of a non-replay viewer. Never blocks, never throws and never changes what the
     * caller is answered: the read is served from the held graph, and at most a debounced, single-flight, system
     * rebuild is started in the background.
     */
    public void requestRefreshCheck() {
        RefreshTrigger trigger = refreshTrigger;
        if (trigger != null) {
            try {
                trigger.topologyRead();
            } catch (RuntimeException ignored) {
                // a failed trigger must never fail a read
            }
        }
    }

    /** Every override with its status: APPLIED / NO_EFFECT / NO_MATCH from the held graph, else REVOKED or PENDING_REBUILD. */
    public List<OverrideView> overrides() {
        TopologyGraph graph = current.get();
        List<OverrideView> out = new ArrayList<>();
        for (TopologyLinkOverride o : repository.listOverrides()) {
            if (!o.active()) {
                out.add(new OverrideView(o, "REVOKED", Optional.empty()));
                continue;
            }
            Optional<TopologyGraph.OverrideResult> result = graph == null ? Optional.empty() : graph.override(o.overrideId());
            out.add(result.map(r -> new OverrideView(o, r.status().name(), r.reason().map(Enum::name)))
                    .orElse(new OverrideView(o, "PENDING_REBUILD", Optional.empty())));
        }
        return out;
    }

    public TopologyLinkOverride createOverride(OverrideRequest request, String actorFingerprint) {
        Objects.requireNonNull(actorFingerprint, "actorFingerprint");
        if (!TopologyLinkOverride.ACTIONS.contains(request.action())
                || !TopologyLinkOverride.LINK_KINDS.contains(request.linkKind())
                || !TopologyLinkOverride.REASON_CODES.contains(request.reasonCode())) {
            throw new Refusal("INVALID_REQUEST", 400);
        }
        for (String value : List.of(request.aDeviceId(), request.aContext(), request.aInterfaceName(),
                request.bDeviceId(), request.bContext(), request.bInterfaceName())) {
            if (!boundedToken(value, 128)) {
                throw new Refusal("INVALID_REQUEST", 400);
            }
        }
        Optional<String> subnet = Optional.empty();
        if (request.assumedSubnet().isPresent()) {
            String text = request.assumedSubnet().get().strip();
            Optional<Ipv4Prefix.HostAddress> parsed = Ipv4Prefix.parseHost(text);
            if (parsed.isEmpty() || parsed.get().length().isEmpty()) {
                throw new Refusal("INVALID_REQUEST", 400);
            }
            subnet = parsed.get().prefix().map(Ipv4Prefix::toString);
        }
        Optional<String> note = request.note().map(String::strip).filter(n -> !n.isEmpty());
        if (note.isPresent() && (note.get().length() > TopologyLinkOverride.NOTE_MAX
                || note.get().chars().anyMatch(ch -> Character.isISOControl(ch) && ch != '\n'))) {
            throw new Refusal("INVALID_REQUEST", 400);
        }
        if (request.aDeviceId().equals(request.bDeviceId()) && request.aContext().equals(request.bContext())
                && request.aInterfaceName().equals(request.bInterfaceName())) {
            throw new Refusal("INVALID_REQUEST", 400);
        }
        if (devices.find(request.aDeviceId()).isEmpty() || devices.find(request.bDeviceId()).isEmpty()) {
            throw new Refusal("DEVICE_NOT_FOUND", 404);
        }
        TopologyLinkOverride override = new TopologyLinkOverride("tlo-" + UUID.randomUUID(), request.action(),
                request.linkKind(), Optional.of(request.aDeviceId()), request.aContext(), request.aInterfaceName(),
                Optional.of(request.bDeviceId()), request.bContext(), request.bInterfaceName(), subnet,
                request.reasonCode(), note, actorFingerprint, clock.instant(), Optional.empty(), Optional.empty(),
                Optional.empty());
        repository.insertOverride(override, actorFingerprint, ActionRegistry.TOPOLOGY_OVERRIDE_WRITE);
        return override;
    }

    public void revokeOverride(String overrideId, String actorFingerprint) {
        if (!boundedToken(overrideId, 64) || repository.findOverride(overrideId).isEmpty()) {
            throw new Refusal("OVERRIDE_NOT_FOUND", 404);
        }
        if (!repository.revokeOverride(overrideId, actorFingerprint, ActionRegistry.TOPOLOGY_OVERRIDE_WRITE,
                clock.instant())) {
            throw new Refusal("OVERRIDE_ALREADY_REVOKED", 409);
        }
    }

    private static boolean boundedToken(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
                && value.chars().noneMatch(Character::isISOControl) && value.strip().equals(value);
    }
}
