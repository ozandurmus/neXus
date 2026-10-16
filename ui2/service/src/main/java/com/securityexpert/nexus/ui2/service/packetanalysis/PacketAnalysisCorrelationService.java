package com.securityexpert.nexus.ui2.service.packetanalysis;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.engine.CaseQueries;
import com.securityexpert.nexus.ui2.packetanalysis.engine.CaseRunner;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Evidence;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.RunRow;
import com.securityexpert.nexus.ui2.platform.PacketAnalysisPaths;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Section;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Target;
import com.securityexpert.nexus.ui2.service.policy.PolicyQueryService;

/**
 * Service-side Packet Analysis x neXus inventory and policy correlation (PO decision record 2026-10-15 §7 phase 4;
 * plan step S18).
 *
 * <p>Read-only against neXus PostgreSQL data ({@link DeviceRepository}, {@link DeviceInventoryRepository},
 * {@link PolicyQueryService}); the {@code ui2/packet-analysis} engine module remains DB-free. Never issues a device
 * command or triggers collection. Every match names its concrete evidence ({@code interface_address_equality},
 * {@code interface_subnet_equality}, {@code mac_equality}, {@code rule_5tuple_containment}) and fails closed to
 * {@code MATCH}, {@code AMBIGUOUS}, {@code NOT_EVALUABLE}, or {@code NO_MATCH}.</p>
 */
public final class PacketAnalysisCorrelationService {

    public static final String STATUS_MATCH = "MATCH";
    public static final String STATUS_AMBIGUOUS = "AMBIGUOUS";
    public static final String STATUS_NOT_EVALUABLE = "NOT_EVALUABLE";
    public static final String STATUS_NO_MATCH = "NO_MATCH";

    public static final String EVIDENCE_INTERFACE_ADDRESS = "interface_address_equality";
    public static final String EVIDENCE_INTERFACE_SUBNET = "interface_subnet_equality";
    public static final String EVIDENCE_MAC_EQUALITY = "mac_equality";
    public static final String EVIDENCE_RULE_5TUPLE = "rule_5tuple_containment";

    /** Policy snapshots older than 7 days relative to reference evaluation time are flagged stale. */
    public static final Duration POLICY_STALE_THRESHOLD = Duration.ofDays(7);

    private static final Pattern FULL_MAC_PATTERN = Pattern.compile("^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$");
    private static final Pattern OUI_ONLY_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{2}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}(?::(?:\\*\\*|xx|XX|\\?\\?)){0,3}$");
    private static final Pattern HUMAN_TUPLE_PATTERN = Pattern.compile(
            "^\\s*\\[?([^\\]\\s]+)\\]?:([0-9]+)\\s*(?:->|\u2192)\\s*\\[?([^\\]\\s]+)\\]?:([0-9]+)\\s*\\(([A-Za-z0-9]+)\\)\\s*$");

    private final PacketAnalysisRepository caseRepository;
    private final PacketCaptureStore captureStore;
    private final Supplier<List<DeviceSummaryRecord>> devicesSupplier;
    private final java.util.function.Function<List<String>, List<InventoryRun>> inventorySupplier;
    private final Supplier<List<PolicySnapshot>> policySupplier;
    private final Supplier<Instant> clock;
    private final ObjectMapper json = new ObjectMapper();

    public PacketAnalysisCorrelationService(
            PacketAnalysisRepository caseRepository,
            PacketCaptureStore captureStore,
            DeviceRepository deviceRepository,
            DeviceInventoryRepository inventoryRepository,
            PolicyQueryService policyQueryService) {
        this(
                caseRepository,
                captureStore,
                deviceRepository == null ? List::of : deviceRepository::listAll,
                deviceIds -> loadInventoryRuns(inventoryRepository, deviceIds),
                () -> loadPolicySnapshots(policyQueryService),
                Instant::now);
    }

    public PacketAnalysisCorrelationService(
            PacketAnalysisRepository caseRepository,
            PacketCaptureStore captureStore,
            Supplier<List<DeviceSummaryRecord>> devicesSupplier,
            java.util.function.Function<List<String>, List<InventoryRun>> inventorySupplier,
            Supplier<List<PolicySnapshot>> policySupplier,
            Supplier<Instant> clock) {
        this.caseRepository = Objects.requireNonNull(caseRepository, "caseRepository");
        this.captureStore = Objects.requireNonNull(captureStore, "captureStore");
        this.devicesSupplier = Objects.requireNonNull(devicesSupplier, "devicesSupplier");
        this.inventorySupplier = Objects.requireNonNull(inventorySupplier, "inventorySupplier");
        this.policySupplier = Objects.requireNonNull(policySupplier, "policySupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Empty no-inventory correlation service used when controller is instantiated without inventory beans. */
    public static PacketAnalysisCorrelationService empty(
            PacketAnalysisRepository caseRepository, PacketCaptureStore captureStore) {
        return new PacketAnalysisCorrelationService(
                caseRepository, captureStore, List::of, ids -> List.of(), List::of, Instant::now);
    }

    private static List<InventoryRun> loadInventoryRuns(
            DeviceInventoryRepository repo, List<String> deviceIds) {
        if (repo == null || deviceIds == null || deviceIds.isEmpty()) {
            return List.of();
        }
        try {
            List<InventoryRun> runs = repo.findLatestRuns(deviceIds);
            if (runs != null && !runs.isEmpty()) {
                return runs;
            }
        } catch (RuntimeException ignored) {
            // Fall back to per-device lookup if batch is unsupported by a test double
        }
        List<InventoryRun> out = new ArrayList<>();
        for (String id : deviceIds) {
            try {
                repo.findLatestRun(id).ifPresent(out::add);
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    private static List<PolicySnapshot> loadPolicySnapshots(PolicyQueryService policies) {
        if (policies == null) {
            return List.of();
        }
        List<PolicySnapshot> out = new ArrayList<>();
        try {
            for (PolicySnapshot.Metadata meta : policies.catalog()) {
                if (meta != null && meta.id() != null) {
                    policies.find(meta.id()).ifPresent(out::add);
                }
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    /**
     * Post-analysis correlation hook: computes the {@code nexus_correlation} section for {@code caseId} after an
     * analysis run completes and writes it into the stored case result ({@code project.duckdb} settings and
     * {@code report.json}).
     */
    public Map<String, Object> onAnalysisCompleted(String caseId) {
        return correlateAndStore(caseId);
    }

    /**
     * Computes the {@code nexus_correlation} section for {@code caseId} against current neXus inventory and policy
     * snapshots, writes it into the case's {@code project.duckdb} settings and any completed run {@code report.json}
     * files on disk, and returns the correlation map.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> correlateAndStore(String caseId) {
        Path dbPath = PacketAnalysisPaths.projectDuckDb(captureStore.root(), caseId);
        if (!Files.isRegularFile(dbPath)) {
            throw new IllegalStateException("ANALYSIS_NOT_READY");
        }
        Map<String, Object> correlation;
        try (Connection conn = Engine.openReadOnly(dbPath);
             Db db = new Db(conn)) {
            correlation = computeCorrelation(caseId, db);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to correlate case " + caseId + ": " + e.getMessage(), e);
        }

        // Persist into project.duckdb settings ('nexus_correlation' and inside 'report') when writable
        try (Connection conn = Engine.open(dbPath);
             Db db = new Db(conn)) {
            String corrJson = PyJsonWriter.dumps(correlation, false);
            db.exec("INSERT OR REPLACE INTO settings VALUES ('nexus_correlation', ?)", corrJson);
            Object rawReport = CaseQueries.settingGet(db, "report");
            if (rawReport instanceof Map<?, ?> reportMap) {
                Map<String, Object> mutableReport = new LinkedHashMap<>((Map<String, Object>) reportMap);
                mutableReport.put("nexus_correlation", correlation);
                db.exec("INSERT OR REPLACE INTO settings VALUES ('report', ?)", PyJsonWriter.dumps(mutableReport, false));
            }
        } catch (Exception ignored) {
            // Read-only volume or concurrent reader: still update report.json and return computed correlation
        }

        // Persist into completed run report.json files on the analysis volume
        try {
            for (RunRow run : caseRepository.runs(caseId)) {
                if ("COMPLETED".equals(run.state())) {
                    Path reportFile = captureStore.reportFile(caseId, run.jobId());
                    if (Files.isRegularFile(reportFile)) {
                        Map<String, Object> report = json.readValue(
                                reportFile.toFile(), new TypeReference<Map<String, Object>>() { });
                        report.put("nexus_correlation", correlation);
                        Path partial = reportFile.resolveSibling(".report.json.corr.partial");
                        Files.writeString(partial, json.writeValueAsString(report), StandardCharsets.UTF_8);
                        Files.move(partial, reportFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }

        return CaseRunner.toStandardMap(correlation);
    }

    /**
     * Computes correlation directly from an open DuckDB connection without mutating disk files.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> computeCorrelation(String caseId, Db db) {
        Instant now = clock.get();
        List<DeviceSummaryRecord> devices = Optional.ofNullable(devicesSupplier.get()).orElse(List.of());
        Map<String, DeviceSummaryRecord> deviceById = new LinkedHashMap<>();
        for (DeviceSummaryRecord d : devices) {
            deviceById.put(d.deviceId(), d);
        }
        List<String> deviceIds = new ArrayList<>(deviceById.keySet());
        List<InventoryRun> runs = Optional.ofNullable(inventorySupplier.apply(deviceIds)).orElse(List.of());
        List<PolicySnapshot> snapshots = Optional.ofNullable(policySupplier.get()).orElse(List.of());

        List<FlatInterface> allInterfaces = flattenInterfaces(deviceById, runs);
        boolean hasInventory = !allInterfaces.isEmpty();

        Object rawTopObj = CaseQueries.settingGet(db, "topology");
        Map<String, Object> rawTopology = rawTopObj instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        Topology topology = CaseQueries.loadTopology(db);
        List<Map<String, Object>> captures = CaseQueries.readInventory(db);
        Map<String, Map<String, Object>> captureById = new LinkedHashMap<>();
        for (Map<String, Object> cap : captures) {
            if (cap.get("id") instanceof String cid) {
                captureById.put(cid, cap);
            }
        }

        Map<String, Map<String, Object>> rawPointById = new LinkedHashMap<>();
        if (rawTopology.get("points") instanceof List<?> pts) {
            for (Object item : pts) {
                if (item instanceof Map<?, ?> pm && pm.get("id") instanceof String pid) {
                    rawPointById.put(pid, (Map<String, Object>) pm);
                }
            }
        }

        // Correlate each topology hop / point
        List<Map<String, Object>> hopCorrelations = new ArrayList<>();
        Map<String, Map<String, Object>> hopById = new LinkedHashMap<>();
        for (Topology.Point point : topology.points) {
            Map<String, Object> rawPt = rawPointById.getOrDefault(point.id(), Map.of());
            Map<String, Object> cap = captureById.getOrDefault(point.captureId(), Map.of());
            Map<String, Object> hopCorr = correlateHop(db, point, rawPt, cap, allInterfaces, hasInventory);
            hopCorrelations.add(hopCorr);
            hopById.put(point.id(), hopCorr);
        }

        // Extract flows and correlate each flow's 5-tuple (after confirmed NAT mapping where present)
        List<FlowRecord> flowRecords = extractFlows(db, topology, rawTopology);
        List<Map<String, Object>> flowCorrelations = new ArrayList<>();
        for (FlowRecord flow : flowRecords) {
            flowCorrelations.add(correlateFlow(
                    flow, topology, hopCorrelations, hopById, allInterfaces, deviceById, snapshots, hasInventory, now));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("case_id", caseId);
        out.put("correlated_at", now.toString());
        out.put("inventory_status", hasInventory ? "AVAILABLE" : STATUS_NOT_EVALUABLE);
        out.put("inventory_reason", hasInventory
                ? null
                : "No device inventory data collected in neXus");
        out.put("policy_snapshot_count", snapshots.size());
        out.put("evidence_separation_note",
                "Capture observations prove only what frames were seen at each capture point. "
                        + "Configured inventory and policy rules express management/device configuration intent, "
                        + "never direct proof of runtime packet disposition.");
        out.put("hops", hopCorrelations);
        out.put("flows", flowCorrelations);
        return out;
    }

    // ---- Hop / Capture-Point Correlation -------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> correlateHop(
            Db db,
            Topology.Point point,
            Map<String, Object> rawPt,
            Map<String, Object> capture,
            List<FlatInterface> allInterfaces,
            boolean hasInventory) {
        Set<String> rawMacs = new LinkedHashSet<>();
        collectStrings(rawPt.get("mac_address"), rawMacs);
        collectStrings(rawPt.get("macs"), rawMacs);
        collectStrings(rawPt.get("observed_macs"), rawMacs);

        Map<String, Object> capInv = capture.get("inventory") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        collectStrings(capInv.get("mac_address"), rawMacs);
        collectStrings(capInv.get("macs"), rawMacs);
        collectStrings(capInv.get("observed_macs"), rawMacs);
        if (capInv.get("interfaces") instanceof List<?> ifaces) {
            for (Object ifObj : ifaces) {
                if (ifObj instanceof Map<?, ?> ifMap) {
                    collectStrings(ifMap.get("mac_address"), rawMacs);
                    collectStrings(ifMap.get("mac"), rawMacs);
                }
            }
        }
        collectPacketMacsAndVendorInterfaces(db, point.captureId(), rawMacs);

        List<String> validFullMacs = new ArrayList<>();
        List<String> rejectedOuiMacs = new ArrayList<>();
        for (String mac : rawMacs) {
            String trimmed = mac.strip().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty()) continue;
            if (FULL_MAC_PATTERN.matcher(trimmed).matches()) {
                validFullMacs.add(trimmed);
            } else if (OUI_ONLY_PATTERN.matcher(trimmed).matches() || trimmed.length() <= 11) {
                rejectedOuiMacs.add(trimmed);
            }
        }

        // Collect explicit hop interface addresses/subnets vs observed packet addresses
        Set<String> explicitAddresses = new LinkedHashSet<>();
        if (point.sourceCidr() != null && !point.sourceCidr().isBlank()) {
            explicitAddresses.add(point.sourceCidr().strip());
        }
        collectStrings(rawPt.get("interface_ip"), explicitAddresses);
        collectStrings(rawPt.get("address"), explicitAddresses);
        collectStrings(rawPt.get("addresses"), explicitAddresses);
        collectStrings(rawPt.get("observed_addresses"), explicitAddresses);
        collectStrings(capInv.get("interface_addresses"), explicitAddresses);
        collectStrings(capInv.get("addresses"), explicitAddresses);

        Set<String> packetIps = collectCapturePacketIps(db, point.captureId());
        collectStrings(capInv.get("ips"), packetIps);
        collectStrings(capInv.get("observed_ips"), packetIps);
        collectStrings(rawPt.get("observed_ips"), packetIps);

        Set<String> allObservedAddressStrings = new LinkedHashSet<>(explicitAddresses);
        allObservedAddressStrings.addAll(packetIps);

        String captureName = capture.get("name") instanceof String s ? s : null;
        String effectiveVendorIface = point.vendorInterface();
        if ((effectiveVendorIface == null || effectiveVendorIface.isBlank())
                && rawPt.get("vendor_interface") instanceof String vi && !vi.isBlank()) {
            effectiveVendorIface = vi.strip();
        }

        Map<String, Object> captureProves = new LinkedHashMap<>();
        captureProves.put("point_id", point.id());
        captureProves.put("point_label", point.label());
        captureProves.put("capture_id", point.captureId());
        captureProves.put("capture_name", captureName);
        captureProves.put("side", point.side());
        captureProves.put("vendor_interface", effectiveVendorIface);
        captureProves.put("observed_macs", validFullMacs);
        captureProves.put("observed_addresses", new ArrayList<>(allObservedAddressStrings));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("point_id", point.id());
        result.put("point_label", point.label());
        result.put("capture_id", point.captureId());
        result.put("capture_name", captureName);
        result.put("side", point.side());
        result.put("capture_proves", captureProves);

        if (!hasInventory) {
            result.put("status", STATUS_NOT_EVALUABLE);
            result.put("reason", "No device inventory data collected in neXus");
            result.put("evidence", List.of());
            result.put("configured_inventory_says", null);
            result.put("candidates", List.of());
            return result;
        }

        // Parse observed addresses into IpIntervals
        List<ParsedAddress> parsedExplicit = parseAddressList(explicitAddresses);
        List<ParsedAddress> parsedPacket = parseAddressList(packetIps);

        List<HopCandidateMatch> candidateMatches = new ArrayList<>();
        boolean sawOuiOnlySimilarity = !rejectedOuiMacs.isEmpty();

        for (FlatInterface candidate : allInterfaces) {
            Set<String> evidences = new LinkedHashSet<>();
            List<String> matchedAddresses = new ArrayList<>();

            // 1. Check MAC equality (full 6-octet equality ONLY; OUI-only is never a match)
            String invMac = candidate.iface.macAddress().map(m -> m.strip().toLowerCase(Locale.ROOT)).orElse(null);
            boolean macConflict = false;
            if (invMac != null && FULL_MAC_PATTERN.matcher(invMac).matches()) {
                if (!validFullMacs.isEmpty()) {
                    if (validFullMacs.contains(invMac)) {
                        evidences.add(EVIDENCE_MAC_EQUALITY);
                    } else {
                        if (validFullMacs.stream().anyMatch(m -> m.substring(0, 8).equals(invMac.substring(0, 8)))) {
                            sawOuiOnlySimilarity = true;
                        }
                        // If the hop explicitly observed only its own NIC MAC(s) and none match this interface's MAC,
                        // record macConflict so an interface on the same subnet with a different MAC isn't falsely
                        // preferred when another interface has exact MAC equality.
                        macConflict = true;
                    }
                } else if (!rejectedOuiMacs.isEmpty()) {
                    String invOui = invMac.substring(0, 8);
                    if (rejectedOuiMacs.stream().anyMatch(m -> m.startsWith(invOui))) {
                        sawOuiOnlySimilarity = true;
                    }
                }
            }

            // 2. Check interface address equality and subnet equality
            for (ParsedAddress invAddr : candidate.parsedAddresses) {
                // Check explicit hop addresses/CIDRs first
                for (ParsedAddress obs : parsedExplicit) {
                    if (!invAddr.family.equals(obs.family)) continue;
                    if (!obs.cidrNotation && invAddr.hostAddress.equals(obs.hostAddress)) {
                        evidences.add(EVIDENCE_INTERFACE_ADDRESS);
                        matchedAddresses.add(invAddr.raw);
                    } else if (obs.cidrNotation && obs.prefixLength == invAddr.maxBits
                            && invAddr.hostAddress.equals(obs.hostAddress)) {
                        evidences.add(EVIDENCE_INTERFACE_ADDRESS);
                        matchedAddresses.add(invAddr.raw);
                    } else if (obs.cidrNotation && invAddr.hostAddress.equals(obs.hostAddress)
                            && !obs.hostAddress.equals(obs.networkStart)) {
                        // e.g. hop specified 192.0.2.1/24 (host address + prefix) matching 192.0.2.1/24
                        evidences.add(EVIDENCE_INTERFACE_ADDRESS);
                        evidences.add(EVIDENCE_INTERFACE_SUBNET);
                        matchedAddresses.add(invAddr.raw);
                    } else if (invAddr.prefixLength > 0
                            && invAddr.networkStart.equals(obs.networkStart)
                            && invAddr.networkEnd.equals(obs.networkEnd)) {
                        evidences.add(EVIDENCE_INTERFACE_SUBNET);
                        matchedAddresses.add(invAddr.raw);
                    } else if (invAddr.prefixLength > 0 && invAddr.prefixLength < invAddr.maxBits
                            && !obs.cidrNotation
                            && obs.hostAddress.compareTo(invAddr.networkStart) >= 0
                            && obs.hostAddress.compareTo(invAddr.networkEnd) <= 0) {
                        evidences.add(EVIDENCE_INTERFACE_SUBNET);
                        matchedAddresses.add(invAddr.raw);
                    }
                }
                // Check packet IPs if no explicit address matched yet
                if (parsedExplicit.isEmpty()) {
                    for (ParsedAddress pkt : parsedPacket) {
                        if (!invAddr.family.equals(pkt.family)) continue;
                        if (invAddr.hostAddress.equals(pkt.hostAddress)) {
                            evidences.add(EVIDENCE_INTERFACE_ADDRESS);
                            matchedAddresses.add(invAddr.raw);
                        } else if (invAddr.prefixLength > 0 && invAddr.prefixLength < invAddr.maxBits
                                && pkt.hostAddress.compareTo(invAddr.networkStart) >= 0
                                && pkt.hostAddress.compareTo(invAddr.networkEnd) <= 0) {
                            evidences.add(EVIDENCE_INTERFACE_SUBNET);
                            matchedAddresses.add(invAddr.raw);
                        }
                    }
                }
            }

            if (!evidences.isEmpty()) {
                // If the hop only matched by subnet, but the hop also had a valid full MAC that conflicted with this
                // interface's known MAC (and no MAC equality), do not count subnet-only when MAC contradicts it.
                if (macConflict && !evidences.contains(EVIDENCE_MAC_EQUALITY)
                        && !evidences.contains(EVIDENCE_INTERFACE_ADDRESS)
                        && parsedExplicit.isEmpty()) {
                    continue;
                }
                int tier = evidences.contains(EVIDENCE_MAC_EQUALITY) && evidences.contains(EVIDENCE_INTERFACE_ADDRESS) ? 4
                        : evidences.contains(EVIDENCE_MAC_EQUALITY) || evidences.contains(EVIDENCE_INTERFACE_ADDRESS) ? 3
                        : 2;
                candidateMatches.add(new HopCandidateMatch(
                        candidate,
                        new ArrayList<>(evidences),
                        matchedAddresses.stream().distinct().toList(),
                        tier));
            }
        }

        if (candidateMatches.isEmpty()) {
            String noMatchReason = sawOuiOnlySimilarity
                    ? "OUI-only MAC prefix similarity is insufficient evidence; full 6-octet MAC or interface address/subnet equality is required"
                    : "No neXus inventory interface matched the hop's observed MACs or addresses/subnets";
            result.put("status", STATUS_NO_MATCH);
            result.put("reason", noMatchReason);
            result.put("evidence", List.of());
            result.put("configured_inventory_says", null);
            result.put("candidates", List.of());
            return result;
        }

        int maxTier = candidateMatches.stream().mapToInt(HopCandidateMatch::tier).max().orElse(0);
        List<HopCandidateMatch> topCandidates = candidateMatches.stream()
                .filter(c -> c.tier() == maxTier)
                .toList();

        // If vendor_interface is explicitly recorded by the capture (e.g. fw1.interface = eth0), check if it narrows
        // only when there is a single exact interface name match AND different interface names; NOTE: two interfaces
        // with the same subnet and no distinguishing capture interface/MAC remain AMBIGUOUS.
        if (topCandidates.size() > 1 && effectiveVendorIface != null && !effectiveVendorIface.isBlank()) {
            String vi = effectiveVendorIface;
            List<HopCandidateMatch> byName = topCandidates.stream()
                    .filter(c -> vi.equals(c.candidate().iface.name()))
                    .toList();
            if (byName.size() == 1) {
                topCandidates = byName;
            }
        }

        List<Map<String, Object>> candidateViews = topCandidates.stream()
                .map(this::toCandidateMap)
                .toList();

        if (topCandidates.size() == 1) {
            HopCandidateMatch winner = topCandidates.get(0);
            Map<String, Object> winnerMap = candidateViews.get(0);
            result.put("status", STATUS_MATCH);
            result.put("reason", "Unique inventory interface match by " + String.join(", ", winner.evidence()));
            result.put("evidence", winner.evidence());
            result.put("device_id", winner.candidate().device.deviceId());
            result.put("hostname", winner.candidate().device.observedHostname().orElse(null));
            result.put("cluster_member_ref", winner.candidate().device.clusterMemberRef().orElse(null));
            result.put("virtual_system", winner.candidate().context);
            result.put("interface_id", winner.candidate().iface.interfaceId());
            result.put("interface_name", winner.candidate().iface.name());
            result.put("zone", winner.candidate().iface.zone().orElse(null));
            result.put("mac_address", winner.candidate().iface.macAddress().orElse(null));
            result.put("interface_addresses", winner.candidate().rawAddresses);
            result.put("matched_addresses", winner.matchedAddresses());
            result.put("configured_inventory_says", winnerMap);
            result.put("candidates", candidateViews);
        } else {
            Set<String> combinedEvidence = new LinkedHashSet<>();
            for (HopCandidateMatch c : topCandidates) combinedEvidence.addAll(c.evidence());
            result.put("status", STATUS_AMBIGUOUS);
            result.put("reason", topCandidates.size()
                    + " candidate inventory interfaces matched with equal evidence ("
                    + String.join(", ", combinedEvidence) + ")");
            result.put("evidence", new ArrayList<>(combinedEvidence));
            result.put("device_id", null);
            result.put("hostname", null);
            result.put("cluster_member_ref", null);
            result.put("virtual_system", null);
            result.put("interface_id", null);
            result.put("interface_name", null);
            result.put("zone", null);
            result.put("mac_address", null);
            result.put("interface_addresses", List.of());
            result.put("configured_inventory_says", Map.of(
                    "status", STATUS_AMBIGUOUS,
                    "candidate_count", candidateViews.size(),
                    "candidates", candidateViews));
            result.put("candidates", candidateViews);
        }
        return result;
    }

    private Map<String, Object> toCandidateMap(HopCandidateMatch match) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("device_id", match.candidate().device.deviceId());
        m.put("hostname", match.candidate().device.observedHostname().orElse(null));
        m.put("cluster_member_ref", match.candidate().device.clusterMemberRef().orElse(null));
        m.put("virtual_system", match.candidate().context);
        m.put("interface_id", match.candidate().iface.interfaceId());
        m.put("interface_name", match.candidate().iface.name());
        m.put("zone", match.candidate().iface.zone().orElse(null));
        m.put("mac_address", match.candidate().iface.macAddress().orElse(null));
        m.put("interface_addresses", match.candidate().rawAddresses);
        m.put("matched_addresses", match.matchedAddresses());
        m.put("evidence", match.evidence());
        return m;
    }

    // ---- Flow & Policy Rule Correlation --------------------------------------------------------------------

    private Map<String, Object> correlateFlow(
            FlowRecord flow,
            Topology topology,
            List<Map<String, Object>> hopCorrelations,
            Map<String, Map<String, Object>> hopById,
            List<FlatInterface> allInterfaces,
            Map<String, DeviceSummaryRecord> deviceById,
            List<PolicySnapshot> snapshots,
            boolean hasInventory,
            Instant now) {
        // Check confirmed NAT mapping in topology
        Tuple5 preNat = flow.tuple;
        Tuple5 postNat = null;
        String natPointA = null;
        String natPointB = null;

        for (Topology.NatMapping mapping : topology.natMappings) {
            Tuple5 tA = parseTuple(mapping.tupleA());
            Tuple5 tB = parseTuple(mapping.tupleB());
            if (tA != null && tB != null) {
                if (tA.matchesFlow(preNat)) {
                    postNat = tB;
                    natPointA = mapping.pointA();
                    natPointB = mapping.pointB();
                    break;
                } else if (tA.reverse().matchesFlow(preNat)) {
                    postNat = tB.reverse();
                    natPointA = mapping.pointA();
                    natPointB = mapping.pointB();
                    break;
                }
            }
        }

        Tuple5 effectiveTuple = postNat != null ? postNat : preNat;

        Map<String, Object> captureProves = new LinkedHashMap<>();
        captureProves.put("flow", flow.flowKey);
        captureProves.put("tuple", flow.displayTuple);
        captureProves.put("proto", preNat.proto);
        captureProves.put("pre_nat_src", preNat.src);
        captureProves.put("pre_nat_sport", preNat.sport);
        captureProves.put("pre_nat_dst", preNat.dst);
        captureProves.put("pre_nat_dport", preNat.dport);
        captureProves.put("nat_confirmed", postNat != null);
        captureProves.put("translated_src", postNat != null ? postNat.src : null);
        captureProves.put("translated_sport", postNat != null ? postNat.sport : null);
        captureProves.put("translated_dst", postNat != null ? postNat.dst : null);
        captureProves.put("translated_dport", postNat != null ? postNat.dport : null);
        captureProves.put("nat_point_a", natPointA);
        captureProves.put("nat_point_b", natPointB);
        captureProves.put("observed_points", flow.observedPoints);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flow", flow.flowKey);
        out.put("tuple", flow.displayTuple);
        out.put("proto", effectiveTuple.proto);
        out.put("src", effectiveTuple.src);
        out.put("sport", effectiveTuple.sport);
        out.put("dst", effectiveTuple.dst);
        out.put("dport", effectiveTuple.dport);
        out.put("nat_confirmed", postNat != null);
        out.put("pre_nat_src", preNat.src);
        out.put("pre_nat_sport", preNat.sport);
        out.put("pre_nat_dst", preNat.dst);
        out.put("pre_nat_dport", preNat.dport);
        out.put("post_nat_src", effectiveTuple.src);
        out.put("post_nat_sport", effectiveTuple.sport);
        out.put("post_nat_dst", effectiveTuple.dst);
        out.put("post_nat_dport", effectiveTuple.dport);
        out.put("capture_proves", captureProves);

        if (!hasInventory && snapshots.isEmpty()) {
            out.put("status", STATUS_NOT_EVALUABLE);
            out.put("reason", "No device inventory or policy snapshots collected in neXus");
            out.put("evidence", List.of());
            out.put("configured_policy_says", null);
            out.put("firewall_matches", List.of());
            return out;
        }

        // Determine candidate firewall devices along the path (from correlated hops first; otherwise from
        // inventory interfaces matching the flow's source/destination subnets or policy targets)
        Set<String> targetDeviceIds = new LinkedHashSet<>();
        Set<String> matchedHopDeviceIds = new LinkedHashSet<>();
        List<Map<String, Object>> ambiguousHopCandidates = new ArrayList<>();
        for (Map<String, Object> hop : hopCorrelations) {
            if (hop.get("device_id") instanceof String did && !did.isBlank()) {
                targetDeviceIds.add(did);
                matchedHopDeviceIds.add(did);
            } else if (STATUS_AMBIGUOUS.equals(hop.get("status"))) {
                if (hop.get("candidates") instanceof List<?> cands) {
                    for (Object c : cands) {
                        if (c instanceof Map<?, ?> cm) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> castCm = (Map<String, Object>) cm;
                            ambiguousHopCandidates.add(castCm);
                            if (castCm.get("device_id") instanceof String cdid && !cdid.isBlank()) {
                                targetDeviceIds.add(cdid);
                            }
                        }
                    }
                }
            }
        }
        ParsedAddress flowSrcAddr = parseSingleAddress(preNat.src);
        ParsedAddress flowDstAddr = parseSingleAddress(effectiveTuple.dst);
        if (targetDeviceIds.isEmpty()) {
            // Check if any inventory interface subnet contains the flow's src or dst
            for (FlatInterface flat : allInterfaces) {
                for (ParsedAddress inv : flat.parsedAddresses) {
                    if (inv.containsAddress(flowSrcAddr) || inv.containsAddress(flowDstAddr)) {
                        targetDeviceIds.add(flat.device.deviceId());
                    }
                }
            }
        }
        if (targetDeviceIds.isEmpty() && !snapshots.isEmpty()) {
            for (PolicySnapshot snap : snapshots) {
                if (snap.metadata().targets() != null) {
                    for (Target t : snap.metadata().targets()) {
                        if (t.deviceId() != null && !t.deviceId().isBlank()) {
                            targetDeviceIds.add(t.deviceId());
                        }
                    }
                }
                if (snap.metadata().sourceId() != null && !snap.metadata().sourceId().isBlank()) {
                    targetDeviceIds.add(snap.metadata().sourceId());
                }
            }
        }

        if (targetDeviceIds.isEmpty()) {
            out.put("status", STATUS_NOT_EVALUABLE);
            out.put("reason", "No correlated firewall device or policy target for this flow");
            out.put("evidence", List.of());
            out.put("configured_policy_says", null);
            out.put("firewall_matches", List.of());
            return out;
        }

        boolean hasAmbiguousFlowFirewall = matchedHopDeviceIds.isEmpty() && targetDeviceIds.size() > 1;
        if (!hasAmbiguousFlowFirewall && targetDeviceIds.size() == 1) {
            String singleDevId = targetDeviceIds.iterator().next();
            List<FlatInterface> devIfaces = allInterfaces.stream()
                    .filter(f -> singleDevId.equals(f.device.deviceId()))
                    .toList();
            long sameSrcSubnetCount = flowSrcAddr == null ? 0 : devIfaces.stream()
                    .filter(f -> f.parsedAddresses.stream().anyMatch(a -> a.containsAddress(flowSrcAddr)))
                    .count();
            long sameDstSubnetCount = flowDstAddr == null ? 0 : devIfaces.stream()
                    .filter(f -> f.parsedAddresses.stream().anyMatch(a -> a.containsAddress(flowDstAddr)))
                    .count();
            if (sameSrcSubnetCount > 1 || sameDstSubnetCount > 1) {
                hasAmbiguousFlowFirewall = true;
            }
        }

        List<Map<String, Object>> firewallMatches = new ArrayList<>();
        for (String deviceId : targetDeviceIds) {
            DeviceSummaryRecord device = deviceById.get(deviceId);
            firewallMatches.add(correlateFlowOnFirewall(
                    deviceId, device, preNat, effectiveTuple, postNat != null,
                    hopCorrelations, allInterfaces, snapshots, now));
        }

        if (hasAmbiguousFlowFirewall) {
            out.put("status", STATUS_AMBIGUOUS);
            out.put("reason", "Multiple candidate firewalls/interfaces matched the flow path with equal evidence");
            out.put("evidence", List.of(EVIDENCE_INTERFACE_SUBNET));
            out.put("device_id", null);
            out.put("hostname", null);
            out.put("cluster_member_ref", null);
            out.put("ingress_interface_id", null);
            out.put("ingress_interface_name", null);
            out.put("source_zone", null);
            out.put("egress_interface_id", null);
            out.put("egress_interface_name", null);
            out.put("destination_zone", null);
            out.put("policy_id", null);
            out.put("policy_name", null);
            out.put("policy_status", null);
            out.put("policy_stale", false);
            out.put("policy_incomplete", false);
            out.put("rule_id", null);
            out.put("rule_number", null);
            out.put("rule_name", null);
            out.put("section_id", null);
            out.put("section_name", null);
            out.put("action", null);
            out.put("configured_policy_says", Map.of(
                    "status", STATUS_AMBIGUOUS,
                    "candidate_count", firewallMatches.size(),
                    "candidates", firewallMatches));
            out.put("candidates", !ambiguousHopCandidates.isEmpty() ? ambiguousHopCandidates : firewallMatches);
            out.put("firewall_matches", firewallMatches);
            return out;
        }

        // Summarize top-level flow status from the firewall evaluations
        Map<String, Object> primary = firewallMatches.get(0);
        out.put("status", primary.get("status"));
        out.put("reason", primary.get("reason"));
        out.put("evidence", primary.get("evidence"));
        out.put("device_id", primary.get("device_id"));
        out.put("hostname", primary.get("hostname"));
        out.put("cluster_member_ref", primary.get("cluster_member_ref"));
        out.put("ingress_interface_id", primary.get("ingress_interface_id"));
        out.put("ingress_interface_name", primary.get("ingress_interface_name"));
        out.put("source_zone", primary.get("source_zone"));
        out.put("egress_interface_id", primary.get("egress_interface_id"));
        out.put("egress_interface_name", primary.get("egress_interface_name"));
        out.put("destination_zone", primary.get("destination_zone"));
        out.put("policy_id", primary.get("policy_id"));
        out.put("policy_name", primary.get("policy_name"));
        out.put("policy_status", primary.get("policy_status"));
        out.put("policy_stale", primary.get("policy_stale"));
        out.put("policy_incomplete", primary.get("policy_incomplete"));
        out.put("rule_id", primary.get("rule_id"));
        out.put("rule_number", primary.get("rule_number"));
        out.put("rule_name", primary.get("rule_name"));
        out.put("section_id", primary.get("section_id"));
        out.put("section_name", primary.get("section_name"));
        out.put("action", primary.get("action"));
        out.put("configured_policy_says", primary.get("configured_policy_says"));
        out.put("candidates", primary.get("candidates"));
        out.put("firewall_matches", firewallMatches);
        return out;
    }

    private Map<String, Object> correlateFlowOnFirewall(
            String deviceId,
            DeviceSummaryRecord device,
            Tuple5 preNat,
            Tuple5 effectiveTuple,
            boolean natConfirmed,
            List<Map<String, Object>> hopCorrelations,
            List<FlatInterface> allInterfaces,
            List<PolicySnapshot> snapshots,
            Instant now) {
        Map<String, Object> fw = new LinkedHashMap<>();
        fw.put("device_id", deviceId);
        fw.put("hostname", device != null ? device.observedHostname().orElse(null) : null);
        fw.put("cluster_member_ref", device != null ? device.clusterMemberRef().orElse(null) : null);

        // Determine ingress / egress interfaces and zones on this device
        String ingressIfaceId = null;
        String ingressIfaceName = null;
        String sourceZone = null;
        String egressIfaceId = null;
        String egressIfaceName = null;
        String destinationZone = null;

        List<Map<String, Object>> deviceHops = hopCorrelations.stream()
                .filter(h -> deviceId.equals(h.get("device_id")) && STATUS_MATCH.equals(h.get("status")))
                .toList();
        for (Map<String, Object> dh : deviceHops) {
            String side = String.valueOf(dh.getOrDefault("side", "both"));
            if ("ingress".equals(side) || (ingressIfaceId == null && !"egress".equals(side))) {
                ingressIfaceId = (String) dh.get("interface_id");
                ingressIfaceName = (String) dh.get("interface_name");
                sourceZone = (String) dh.get("zone");
            } else if ("egress".equals(side) || egressIfaceId == null) {
                egressIfaceId = (String) dh.get("interface_id");
                egressIfaceName = (String) dh.get("interface_name");
                destinationZone = (String) dh.get("zone");
            }
        }

        // Supplement ingress/egress interface & zone from subnet containment on this device if not set by hop side
        ParsedAddress srcAddr = parseSingleAddress(preNat.src);
        ParsedAddress dstAddr = parseSingleAddress(effectiveTuple.dst);
        List<FlatInterface> devIfaces = allInterfaces.stream()
                .filter(f -> deviceId.equals(f.device.deviceId()))
                .toList();
        if (ingressIfaceId == null && srcAddr != null) {
            List<FlatInterface> srcMatches = devIfaces.stream()
                    .filter(f -> f.parsedAddresses.stream().anyMatch(a -> a.containsAddress(srcAddr)))
                    .toList();
            if (srcMatches.size() == 1) {
                ingressIfaceId = srcMatches.get(0).iface.interfaceId();
                ingressIfaceName = srcMatches.get(0).iface.name();
                if (sourceZone == null) sourceZone = srcMatches.get(0).iface.zone().orElse(null);
            }
        }
        if (egressIfaceId == null && dstAddr != null) {
            List<FlatInterface> dstMatches = devIfaces.stream()
                    .filter(f -> f.parsedAddresses.stream().anyMatch(a -> a.containsAddress(dstAddr)))
                    .toList();
            if (dstMatches.size() == 1) {
                egressIfaceId = dstMatches.get(0).iface.interfaceId();
                egressIfaceName = dstMatches.get(0).iface.name();
                if (destinationZone == null) destinationZone = dstMatches.get(0).iface.zone().orElse(null);
            }
        }

        fw.put("ingress_interface_id", ingressIfaceId);
        fw.put("ingress_interface_name", ingressIfaceName);
        fw.put("source_zone", sourceZone);
        fw.put("egress_interface_id", egressIfaceId);
        fw.put("egress_interface_name", egressIfaceName);
        fw.put("destination_zone", destinationZone);

        // Find PolicySnapshot(s) targeting this deviceId
        List<PolicySnapshot> deviceSnapshots = snapshots.stream()
                .filter(s -> isSnapshotForDevice(s, deviceId))
                .sorted(Comparator.comparing((PolicySnapshot s) -> Objects.requireNonNullElse(s.metadata().collectedAt(), "")).reversed())
                .toList();

        if (deviceSnapshots.isEmpty()) {
            fw.put("status", STATUS_NOT_EVALUABLE);
            fw.put("reason", "No collected policy snapshot for firewall " + deviceId);
            fw.put("evidence", List.of());
            fw.put("policy_id", null);
            fw.put("policy_name", null);
            fw.put("policy_status", "MISSING");
            fw.put("policy_stale", false);
            fw.put("policy_incomplete", false);
            fw.put("rule_id", null);
            fw.put("rule_number", null);
            fw.put("rule_name", null);
            fw.put("section_id", null);
            fw.put("section_name", null);
            fw.put("action", null);
            fw.put("configured_policy_says", null);
            fw.put("candidates", List.of());
            return fw;
        }

        PolicySnapshot snapshot = deviceSnapshots.get(0);
        PolicyHealth health = assessPolicyHealth(snapshot, deviceId, now);

        fw.put("policy_id", snapshot.metadata().id());
        fw.put("policy_name", snapshot.metadata().name());
        fw.put("policy_collected_at", snapshot.metadata().collectedAt());
        fw.put("policy_status", health.status());
        fw.put("policy_stale", health.stale());
        fw.put("policy_incomplete", health.incomplete());

        RuleEvaluationResult eval = evaluateFirstMatchRule(
                snapshot, effectiveTuple, sourceZone, destinationZone, natConfirmed);

        boolean incomplete = health.incomplete() || eval.incompleteEncountered();
        fw.put("policy_incomplete", incomplete);
        if (incomplete && "OK".equals(health.status())) {
            fw.put("policy_status", "INCOMPLETE");
        }

        if (eval.matchedRule() != null) {
            RuleCandidate rc = eval.matchedRule();
            Map<String, Object> configuredPolicy = toConfiguredPolicyMap(
                    snapshot, rc, health.stale(), incomplete, health.reason(), eval.incompleteReason());

            if (health.stale() || incomplete) {
                String why = health.stale() && incomplete
                        ? "Policy snapshot is stale and incomplete (" + health.reason() + ")"
                        : health.stale()
                                ? "Policy snapshot is stale (" + health.reason() + "); current enforcement cannot be proven"
                                : "Policy snapshot is incomplete ("
                                        + (eval.incompleteReason() != null ? eval.incompleteReason() : health.reason()) + ")";
                fw.put("status", STATUS_NOT_EVALUABLE);
                fw.put("reason", why);
            } else {
                fw.put("status", STATUS_MATCH);
                fw.put("reason", "First-match rule by " + EVIDENCE_RULE_5TUPLE
                        + (natConfirmed ? " (using confirmed post-NAT 5-tuple)" : ""));
            }
            fw.put("evidence", List.of(EVIDENCE_RULE_5TUPLE));
            fw.put("rule_id", rc.rule.id());
            fw.put("rule_number", rc.rule.number());
            fw.put("rule_name", rc.rule.name());
            fw.put("section_id", rc.section.id());
            fw.put("section_name", rc.section.name());
            fw.put("action", rc.rule.action());
            fw.put("configured_policy_says", configuredPolicy);
            fw.put("candidates", List.of(configuredPolicy));
        } else {
            if (health.stale() || incomplete) {
                String why = health.stale()
                        ? "Policy snapshot is stale (" + health.reason() + ")"
                        : "Policy snapshot is incomplete ("
                                + (eval.incompleteReason() != null ? eval.incompleteReason() : health.reason()) + ")";
                fw.put("status", STATUS_NOT_EVALUABLE);
                fw.put("reason", why);
            } else {
                fw.put("status", STATUS_NO_MATCH);
                fw.put("reason", "No rule in policy snapshot " + snapshot.metadata().id() + " contained the flow 5-tuple");
            }
            fw.put("evidence", List.of());
            fw.put("rule_id", null);
            fw.put("rule_number", null);
            fw.put("rule_name", null);
            fw.put("section_id", null);
            fw.put("section_name", null);
            fw.put("action", null);
            fw.put("configured_policy_says", Map.of(
                    "policy_id", snapshot.metadata().id(),
                    "policy_name", Objects.requireNonNullElse(snapshot.metadata().name(), ""),
                    "policy_status", fw.get("policy_status"),
                    "policy_stale", health.stale(),
                    "policy_incomplete", incomplete,
                    "matched", false,
                    "note", "Configured policy intent only; no matching rule found in collected snapshot"));
            fw.put("candidates", List.of());
        }
        return fw;
    }

    private Map<String, Object> toConfiguredPolicyMap(
            PolicySnapshot snapshot,
            RuleCandidate rc,
            boolean stale,
            boolean incomplete,
            String healthReason,
            String evalIncompleteReason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("policy_id", snapshot.metadata().id());
        m.put("policy_name", snapshot.metadata().name());
        m.put("vendor", snapshot.metadata().vendor());
        m.put("collected_at", snapshot.metadata().collectedAt());
        m.put("policy_status", stale ? "STALE" : incomplete ? "INCOMPLETE" : "OK");
        m.put("policy_stale", stale);
        m.put("policy_incomplete", incomplete);
        if (stale || incomplete) {
            m.put("policy_warning", evalIncompleteReason != null ? evalIncompleteReason : healthReason);
        }
        m.put("section_id", rc.section.id());
        m.put("section_name", rc.section.name());
        m.put("rule_id", rc.rule.id());
        m.put("rule_number", rc.rule.number());
        m.put("rule_name", rc.rule.name());
        m.put("action", rc.rule.action());
        m.put("matched_source_objects", rc.matchedSources);
        m.put("matched_destination_objects", rc.matchedDestinations);
        m.put("matched_service_objects", rc.matchedServices);
        m.put("evaluated_post_nat", rc.evaluatedPostNat);
        m.put("evidence", List.of(EVIDENCE_RULE_5TUPLE));
        m.put("note", "Configured management policy intent only; does not by itself prove runtime packet disposition");
        return m;
    }

    private static boolean isSnapshotForDevice(PolicySnapshot snapshot, String deviceId) {
        if (snapshot == null || snapshot.metadata() == null || deviceId == null) {
            return false;
        }
        if (deviceId.equals(snapshot.metadata().sourceId())) {
            return true;
        }
        if (snapshot.metadata().targets() != null) {
            for (Target t : snapshot.metadata().targets()) {
                if (t != null && deviceId.equals(t.deviceId())) {
                    return true;
                }
            }
        }
        return false;
    }

    private record PolicyHealth(String status, boolean stale, boolean incomplete, String reason) {
    }

    private PolicyHealth assessPolicyHealth(PolicySnapshot snapshot, String deviceId, Instant now) {
        boolean incomplete = false;
        String incompleteReason = null;

        if (!snapshot.failures().isEmpty()) {
            incomplete = true;
            incompleteReason = "Snapshot collection recorded " + snapshot.failures().size() + " layer failure(s): "
                    + snapshot.failures().get(0).reason();
        } else if (snapshot.sections().isEmpty()) {
            incomplete = true;
            incompleteReason = "Snapshot contains zero policy sections";
        }

        boolean stale = false;
        String staleReason = null;
        if (snapshot.metadata().targets() != null) {
            for (Target t : snapshot.metadata().targets()) {
                if (t != null && deviceId.equals(t.deviceId()) && t.syncStatus() != null) {
                    String sync = t.syncStatus().strip().toUpperCase(Locale.ROOT);
                    if (Set.of("STALE", "OUT_OF_SYNC", "POLICY_OUT_OF_DATE").contains(sync)) {
                        stale = true;
                        staleReason = "Target syncStatus is " + t.syncStatus();
                    } else if (Set.of("INCOMPLETE", "PARTIAL", "FAILED").contains(sync)) {
                        incomplete = true;
                        incompleteReason = "Target syncStatus is " + t.syncStatus();
                    }
                }
            }
        }

        String collectedAt = snapshot.metadata().collectedAt();
        if (collectedAt != null && !collectedAt.isBlank()) {
            try {
                Instant ts = Instant.parse(collectedAt.strip());
                Duration age = Duration.between(ts, now);
                if (age.compareTo(POLICY_STALE_THRESHOLD) > 0) {
                    stale = true;
                    staleReason = "Collected at " + collectedAt + " (" + age.toDays() + " days old, threshold 7 days)";
                }
            } catch (RuntimeException unparseable) {
                stale = true;
                staleReason = "Unparseable collectedAt timestamp";
            }
        } else {
            stale = true;
            staleReason = "Missing collectedAt timestamp";
        }

        String status = stale ? "STALE" : incomplete ? "INCOMPLETE" : "OK";
        String reason = stale ? staleReason : incomplete ? incompleteReason : null;
        return new PolicyHealth(status, stale, incomplete, reason);
    }

    private record RuleCandidate(
            Section section,
            Rule rule,
            List<String> matchedSources,
            List<String> matchedDestinations,
            List<String> matchedServices,
            boolean evaluatedPostNat) {
    }

    private record RuleEvaluationResult(
            RuleCandidate matchedRule,
            boolean incompleteEncountered,
            String incompleteReason) {
    }

    private RuleEvaluationResult evaluateFirstMatchRule(
            PolicySnapshot snapshot,
            Tuple5 tuple,
            String sourceZone,
            String destinationZone,
            boolean natConfirmed) {
        ParsedAddress srcAddr = parseSingleAddress(tuple.src);
        ParsedAddress dstAddr = parseSingleAddress(tuple.dst);
        if (srcAddr == null || dstAddr == null) {
            return new RuleEvaluationResult(null, true, "Flow source or destination IP is invalid");
        }

        Map<String, PolicyObject> objects = snapshot.objects();
        boolean sawIncomplete = false;
        String incompleteReason = null;

        for (Section section : snapshot.sections()) {
            if ("CP NAT rulebase".equals(section.source())) {
                continue;
            }
            for (Rule rule : section.rules()) {
                if (Boolean.FALSE.equals(rule.enabled())) {
                    continue;
                }
                if (rule.enabled() == null) {
                    sawIncomplete = true;
                    incompleteReason = "Rule " + rule.number() + " has unknown enabled state";
                    break;
                }

                // Check zone constraints if rule defines 'from' or 'to'
                if (!zoneMatches(rule.extras().get("from"), sourceZone)) {
                    continue;
                }
                if (!zoneMatches(rule.extras().get("to"), destinationZone)) {
                    continue;
                }

                CellMatch srcMatch = matchAddressCell(rule.source(), srcAddr, objects);
                if (srcMatch.incomplete) {
                    sawIncomplete = true;
                    incompleteReason = srcMatch.reason;
                }
                if (!srcMatch.matched) {
                    continue;
                }

                CellMatch dstMatch = matchAddressCell(rule.destination(), dstAddr, objects);
                if (dstMatch.incomplete) {
                    sawIncomplete = true;
                    incompleteReason = dstMatch.reason;
                }
                if (!dstMatch.matched) {
                    continue;
                }

                CellMatch svcMatch = matchServiceCell(rule.service(), tuple.proto, tuple.dport, objects);
                if (svcMatch.incomplete) {
                    sawIncomplete = true;
                    incompleteReason = svcMatch.reason;
                }
                if (!svcMatch.matched) {
                    continue;
                }

                return new RuleEvaluationResult(
                        new RuleCandidate(
                                section,
                                rule,
                                srcMatch.matchedNames,
                                dstMatch.matchedNames,
                                svcMatch.matchedNames,
                                natConfirmed),
                        sawIncomplete,
                        incompleteReason);
            }
        }
        return new RuleEvaluationResult(null, sawIncomplete, incompleteReason);
    }

    private static boolean zoneMatches(List<String> ruleZones, String flowZone) {
        if (ruleZones == null || ruleZones.isEmpty() || ruleZones.contains("any") || ruleZones.contains("Any")) {
            return true;
        }
        if (flowZone == null || flowZone.isBlank()) {
            // When the rule constrains zones only if known, or fail closed if flow zone differs
            return true;
        }
        return ruleZones.stream().anyMatch(z -> z.equalsIgnoreCase(flowZone));
    }

    private record CellMatch(boolean matched, boolean incomplete, String reason, List<String> matchedNames) {
    }

    private CellMatch matchAddressCell(Cell cell, ParsedAddress target, Map<String, PolicyObject> objects) {
        if (cell == null || cell.refs().isEmpty()) {
            return new CellMatch(false, true, "Rule has empty address cell", List.of());
        }
        List<String> matchedNames = new ArrayList<>();
        boolean anyIncomplete = false;
        String incReason = null;

        for (String ref : cell.refs()) {
            ObjectEval ev = evaluateAddressObject(ref, target, objects, new HashSet<>());
            if (ev.incomplete) {
                anyIncomplete = true;
                incReason = ev.reason;
            }
            if (ev.matched) {
                matchedNames.add(ev.objectName);
            }
        }
        boolean rawMatched = !matchedNames.isEmpty();
        boolean finalMatched = cell.negated() ? !rawMatched && !anyIncomplete : rawMatched;
        if (cell.negated() && finalMatched) {
            matchedNames = List.of("NOT(" + String.join(",", cell.refs()) + ")");
        }
        return new CellMatch(finalMatched, anyIncomplete, incReason, matchedNames);
    }

    private record ObjectEval(boolean matched, boolean incomplete, String reason, String objectName) {
    }

    private ObjectEval evaluateAddressObject(
            String id, ParsedAddress target, Map<String, PolicyObject> objects, Set<String> visited) {
        if ("any".equalsIgnoreCase(id)) {
            return new ObjectEval(true, false, null, "any");
        }
        if (!visited.add(id) || visited.size() > 32) {
            return new ObjectEval(false, true, "Object group cycle or depth exceeded", id);
        }
        PolicyObject obj = objects.get(id);
        if (obj == null) {
            ParsedAddress inlineAddr = parseAddressOrRange(id);
            if (inlineAddr != null) {
                visited.remove(id);
                return new ObjectEval(inlineAddr.containsAddress(target), false, null, id);
            }
            visited.remove(id);
            return new ObjectEval(false, true, "Referenced policy object " + id + " is missing from snapshot", id);
        }
        if (obj.status() != null && !Set.of("RESOLVED", "OK", "ok").contains(obj.status())) {
            visited.remove(id);
            return new ObjectEval(false, true, "Policy object " + obj.name() + " has status " + obj.status(), obj.name());
        }
        if ("any".equalsIgnoreCase(obj.type())) {
            visited.remove(id);
            return new ObjectEval(true, false, null, obj.name());
        }
        if (Set.of("group", "address-group").contains(obj.type())) {
            boolean incomplete = false;
            String reason = null;
            for (String member : obj.members()) {
                ObjectEval child = evaluateAddressObject(member, target, objects, visited);
                if (child.incomplete) {
                    incomplete = true;
                    reason = child.reason;
                }
                if (child.matched) {
                    visited.remove(id);
                    return new ObjectEval(true, incomplete, reason, obj.name());
                }
            }
            visited.remove(id);
            return new ObjectEval(false, incomplete, reason, obj.name());
        }

        // Leaf address / host / network / address-range object (supports IPv4 and IPv6)
        List<ParsedAddress> intervals = parsePolicyAddressObject(obj);
        if (intervals.isEmpty()) {
            visited.remove(id);
            return new ObjectEval(false, true, "Policy object " + obj.name() + " has no parseable address values", obj.name());
        }
        for (ParsedAddress interval : intervals) {
            if (interval.containsAddress(target)) {
                visited.remove(id);
                return new ObjectEval(true, false, null, obj.name());
            }
        }
        visited.remove(id);
        return new ObjectEval(false, false, null, obj.name());
    }

    private CellMatch matchServiceCell(Cell cell, String proto, int dport, Map<String, PolicyObject> objects) {
        if (cell == null || cell.refs().isEmpty()) {
            return new CellMatch(false, true, "Rule has empty service cell", List.of());
        }
        List<String> matchedNames = new ArrayList<>();
        boolean anyIncomplete = false;
        String incReason = null;

        for (String ref : cell.refs()) {
            ObjectEval ev = evaluateServiceObject(ref, proto, dport, objects, new HashSet<>());
            if (ev.incomplete) {
                anyIncomplete = true;
                incReason = ev.reason;
            }
            if (ev.matched) {
                matchedNames.add(ev.objectName);
            }
        }
        boolean rawMatched = !matchedNames.isEmpty();
        boolean finalMatched = cell.negated() ? !rawMatched && !anyIncomplete : rawMatched;
        return new CellMatch(finalMatched, anyIncomplete, incReason, matchedNames);
    }

    private ObjectEval evaluateServiceObject(
            String id, String proto, int dport, Map<String, PolicyObject> objects, Set<String> visited) {
        if ("any".equalsIgnoreCase(id) || "application-default".equalsIgnoreCase(id)) {
            return new ObjectEval(true, false, null, id);
        }
        if (!visited.add(id) || visited.size() > 32) {
            return new ObjectEval(false, true, "Service group cycle", id);
        }
        String normProto = proto == null ? "" : proto.strip().toLowerCase(Locale.ROOT);
        PolicyObject obj = objects.get(id);
        if (obj == null) {
            if (id.contains("/")) {
                String[] parts = id.split("/", 2);
                String litProto = parts[0].strip().toLowerCase(Locale.ROOT);
                if (Set.of("tcp", "udp", "icmp", "icmpv6", "icmp6").contains(litProto)) {
                    visited.remove(id);
                    boolean matched = litProto.equals(normProto) && portListContains(parts[1], dport);
                    return new ObjectEval(matched, false, null, id);
                }
            }
            visited.remove(id);
            return new ObjectEval(false, true, "Referenced service object " + id + " is missing", id);
        }
        if (obj.status() != null && !Set.of("RESOLVED", "OK", "ok").contains(obj.status())) {
            visited.remove(id);
            return new ObjectEval(false, true, "Service object " + obj.name() + " has status " + obj.status(), obj.name());
        }
        if ("any".equalsIgnoreCase(obj.type())) {
            visited.remove(id);
            return new ObjectEval(true, false, null, obj.name());
        }
        if ("service-group".equals(obj.type()) || "group".equals(obj.type())) {
            boolean incomplete = false;
            String reason = null;
            for (String member : obj.members()) {
                ObjectEval child = evaluateServiceObject(member, proto, dport, objects, visited);
                if (child.incomplete) {
                    incomplete = true;
                    reason = child.reason;
                }
                if (child.matched) {
                    visited.remove(id);
                    return new ObjectEval(true, incomplete, reason, obj.name());
                }
            }
            visited.remove(id);
            return new ObjectEval(false, incomplete, reason, obj.name());
        }

        if ("service-icmp".equals(obj.type()) && "icmp".equals(normProto)) {
            visited.remove(id);
            return new ObjectEval(true, false, null, obj.name());
        }
        if (("service-icmp6".equals(obj.type()) || "service-icmpv6".equals(obj.type()))
                && ("icmpv6".equals(normProto) || "icmp6".equals(normProto))) {
            visited.remove(id);
            return new ObjectEval(true, false, null, obj.name());
        }

        Map<String, String> fields = new LinkedHashMap<>();
        List<String> literals = new ArrayList<>();
        for (String val : obj.values()) {
            int split = val.indexOf(": ");
            if (split >= 0) {
                fields.put(val.substring(0, split).strip(), val.substring(split + 2).strip());
            } else {
                literals.add(val.strip());
            }
        }
        String objProto = fields.getOrDefault("protocol",
                "service-tcp".equals(obj.type()) ? "tcp"
                        : "service-udp".equals(obj.type()) ? "udp" : null);

        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String key = entry.getKey();
            if ("protocol".equals(key)) continue;
            String p = "port".equals(key) ? objProto
                    : "protocol/tcp/port".equals(key) ? "tcp"
                    : "protocol/udp/port".equals(key) ? "udp" : null;
            if (p != null && p.equalsIgnoreCase(normProto) && portListContains(entry.getValue(), dport)) {
                visited.remove(id);
                return new ObjectEval(true, false, null, obj.name());
            }
        }
        for (String lit : literals) {
            if ("any".equalsIgnoreCase(lit)) {
                visited.remove(id);
                return new ObjectEval(true, false, null, obj.name());
            }
            if (lit.contains("/")) {
                String[] parts = lit.split("/", 2);
                if (parts[0].equalsIgnoreCase(normProto) && portListContains(parts[1], dport)) {
                    visited.remove(id);
                    return new ObjectEval(true, false, null, obj.name());
                }
            } else if (objProto != null && objProto.equalsIgnoreCase(normProto) && portListContains(lit, dport)) {
                visited.remove(id);
                return new ObjectEval(true, false, null, obj.name());
            }
        }
        visited.remove(id);
        return new ObjectEval(false, false, null, obj.name());
    }

    private static boolean portListContains(String expr, int targetPort) {
        for (String token : expr.split(",")) {
            String t = token.strip();
            if (t.isEmpty()) continue;
            if ("any".equalsIgnoreCase(t)) return true;
            String[] ends = t.split("-", -1);
            try {
                int a = Integer.parseInt(ends[0].strip());
                int b = ends.length > 1 ? Integer.parseInt(ends[1].strip()) : a;
                if (targetPort >= a && targetPort <= b) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    // ---- DuckDB Extraction Helpers -------------------------------------------------------------------------

    private record Tuple5(String proto, String src, int sport, String dst, int dport) {
        Tuple5 reverse() {
            return new Tuple5(proto, dst, dport, src, sport);
        }

        boolean matchesFlow(Tuple5 other) {
            return other != null
                    && proto.equalsIgnoreCase(other.proto)
                    && ipEquals(src, other.src)
                    && sport == other.sport
                    && ipEquals(dst, other.dst)
                    && dport == other.dport;
        }
    }

    private record FlowRecord(String flowKey, String displayTuple, Tuple5 tuple, List<String> observedPoints) {
    }

    @SuppressWarnings("unchecked")
    private List<FlowRecord> extractFlows(Db db, Topology topology, Map<String, Object> rawTopology) {
        List<FlowRecord> out = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        // 1. Try flow_summary table
        try {
            for (Db.Row row : db.rows("SELECT flow, tuple FROM flow_summary ORDER BY bytes DESC NULLS LAST, flow LIMIT 100")) {
                String flowKey = row.str("flow");
                String displayTuple = row.str("tuple");
                Tuple5 parsed = parseTuple(flowKey != null ? flowKey : displayTuple);
                if (parsed != null && seenKeys.add(flowKey)) {
                    List<String> points = queryFlowPoints(db, flowKey, topology);
                    out.add(new FlowRecord(
                            flowKey,
                            displayTuple != null ? displayTuple : formatTuple(parsed),
                            parsed,
                            points));
                }
            }
        } catch (RuntimeException ignored) {
        }

        // 2. Try obs table if flow_summary had no rows
        if (out.isEmpty()) {
            try {
                for (Db.Row row : db.rows("SELECT DISTINCT flow FROM obs WHERE flow IS NOT NULL ORDER BY flow LIMIT 100")) {
                    String flowKey = row.str("flow");
                    Tuple5 parsed = parseTuple(flowKey);
                    if (parsed != null && seenKeys.add(flowKey)) {
                        List<String> points = queryFlowPoints(db, flowKey, topology);
                        out.add(new FlowRecord(flowKey, formatTuple(parsed), parsed, points));
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }

        // 3. Try packets table if obs had no rows
        if (out.isEmpty()) {
            try {
                for (Db.Row row : db.rows(
                        "SELECT DISTINCT tuple_key, proto, src, sport, dst, dport FROM packets "
                                + "WHERE src IS NOT NULL AND src <> '' AND dst IS NOT NULL AND dst <> '' "
                                + "ORDER BY tuple_key LIMIT 100")) {
                    String proto = Objects.requireNonNullElse(row.str("proto"), "TCP");
                    String src = row.str("src");
                    int sport = row.get("sport") instanceof Number n ? n.intValue() : 0;
                    String dst = row.str("dst");
                    int dport = row.get("dport") instanceof Number n ? n.intValue() : 0;
                    String flowKey = row.str("tuple_key");
                    Tuple5 t = new Tuple5(proto, src, sport, dst, dport);
                    if (flowKey == null || flowKey.isBlank()) {
                        flowKey = PyJsonWriter.compact(List.of(proto, src, sport, dst, dport));
                    }
                    if (seenKeys.add(flowKey)) {
                        out.add(new FlowRecord(flowKey, formatTuple(t), t, new ArrayList<>(topology.forward)));
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }

        // 4. Also check if rawTopology or settings has explicit 'flows' list (useful in lightweight unit fixtures)
        Object explicitFlows = rawTopology.get("flows");
        if (explicitFlows == null) {
            explicitFlows = CaseQueries.settingGet(db, "flows");
        }
        if (explicitFlows instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    Tuple5 t = parseTuple(s);
                    if (t != null && seenKeys.add(s)) {
                        out.add(new FlowRecord(s, formatTuple(t), t, new ArrayList<>(topology.forward)));
                    }
                } else if (item instanceof Map<?, ?> fm) {
                    String proto = fm.get("proto") != null ? String.valueOf(fm.get("proto")) : "TCP";
                    String src = fm.get("src") != null ? String.valueOf(fm.get("src")) : "";
                    int sport = fm.get("sport") instanceof Number n ? n.intValue() : 0;
                    String dst = fm.get("dst") != null ? String.valueOf(fm.get("dst")) : "";
                    int dport = fm.get("dport") instanceof Number n ? n.intValue() : 0;
                    String fk = fm.get("flow") instanceof String fs
                            ? fs
                            : PyJsonWriter.compact(List.of(proto, src, sport, dst, dport));
                    Tuple5 t = new Tuple5(proto, src, sport, dst, dport);
                    if (seenKeys.add(fk)) {
                        out.add(new FlowRecord(fk, formatTuple(t), t, new ArrayList<>(topology.forward)));
                    }
                }
            }
        }
        return out;
    }

    private List<String> queryFlowPoints(Db db, String flowKey, Topology topology) {
        try {
            List<String> pts = new ArrayList<>();
            for (Db.Row r : db.rows("SELECT DISTINCT point FROM obs WHERE flow=? ORDER BY point", flowKey)) {
                if (r.str("point") != null) pts.add(r.str("point"));
            }
            if (!pts.isEmpty()) return pts;
        } catch (RuntimeException ignored) {
        }
        return new ArrayList<>(topology.forward);
    }

    private static String formatTuple(Tuple5 t) {
        return t.src + ":" + t.sport + " -> " + t.dst + ":" + t.dport + " (" + t.proto + ")";
    }

    @SuppressWarnings("unchecked")
    private static Tuple5 parseTuple(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.strip();
        if (trimmed.startsWith("[")) {
            try {
                Object loaded = Evidence.loads(trimmed);
                if (loaded instanceof List<?> parts && parts.size() >= 5) {
                    String proto = String.valueOf(parts.get(0));
                    String src = String.valueOf(parts.get(1));
                    int sport = parts.get(2) instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(parts.get(2)));
                    String dst = String.valueOf(parts.get(3));
                    int dport = parts.get(4) instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(parts.get(4)));
                    return new Tuple5(proto, src, sport, dst, dport);
                }
            } catch (RuntimeException ignored) {
            }
        }
        Matcher m = HUMAN_TUPLE_PATTERN.matcher(trimmed);
        if (m.matches()) {
            return new Tuple5(
                    m.group(5),
                    m.group(1),
                    Integer.parseInt(m.group(2)),
                    m.group(3),
                    Integer.parseInt(m.group(4)));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void collectPacketMacsAndVendorInterfaces(Db db, String captureId, Set<String> macsOut) {
        if (captureId == null || captureId.isBlank()) return;
        try {
            for (Db.Row r : db.rows("SELECT DISTINCT path_fields FROM packets WHERE capture_id=? AND path_fields IS NOT NULL LIMIT 50", captureId)) {
                String pf = r.str("path_fields");
                if (pf != null && pf.startsWith("{")) {
                    Map<String, Object> map = (Map<String, Object>) Evidence.loads(pf);
                    collectStrings(map.get("mac"), macsOut);
                    collectStrings(map.get("mac_address"), macsOut);
                    collectStrings(map.get("eth_src"), macsOut);
                    collectStrings(map.get("eth_dst"), macsOut);
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    private Set<String> collectCapturePacketIps(Db db, String captureId) {
        Set<String> ips = new LinkedHashSet<>();
        if (captureId == null || captureId.isBlank()) return ips;
        try {
            for (Db.Row r : db.rows(
                    "SELECT DISTINCT src AS ip FROM packets WHERE capture_id=? AND src IS NOT NULL AND src <> '' "
                            + "UNION SELECT DISTINCT dst AS ip FROM packets WHERE capture_id=? AND dst IS NOT NULL AND dst <> '' "
                            + "LIMIT 100",
                    captureId, captureId)) {
                if (r.str("ip") != null && !r.str("ip").isBlank()) {
                    ips.add(r.str("ip").strip());
                }
            }
        } catch (RuntimeException ignored) {
        }
        return ips;
    }

    private static void collectStrings(Object source, Collection<String> target) {
        if (source instanceof String s && !s.isBlank()) {
            target.add(s.strip());
        } else if (source instanceof Collection<?> c) {
            for (Object item : c) {
                if (item instanceof String s && !s.isBlank()) {
                    target.add(s.strip());
                } else if (item instanceof Map<?, ?> m && m.get("address") instanceof String addr && !addr.isBlank()) {
                    target.add(addr.strip());
                }
            }
        }
    }

    // ---- Inventory Flattening & Address Math (IPv4 + IPv6, Zero DNS) ---------------------------------------

    private record FlatInterface(
            DeviceSummaryRecord device,
            String context,
            InventoryInterface iface,
            List<String> rawAddresses,
            List<ParsedAddress> parsedAddresses) {
    }

    private record HopCandidateMatch(
            FlatInterface candidate,
            List<String> evidence,
            List<String> matchedAddresses,
            int tier) {
    }

    private List<FlatInterface> flattenInterfaces(
            Map<String, DeviceSummaryRecord> deviceById, List<InventoryRun> runs) {
        List<FlatInterface> out = new ArrayList<>();
        for (InventoryRun run : runs) {
            DeviceSummaryRecord dev = deviceById.get(run.deviceId());
            if (dev == null) continue;
            for (InventoryContext ctx : run.contexts()) {
                for (InventoryInterface iface : ctx.interfaces()) {
                    List<String> rawAddrs = iface.addresses().stream()
                            .map(InventoryAddress::address)
                            .toList();
                    List<ParsedAddress> parsed = parseAddressList(rawAddrs);
                    out.add(new FlatInterface(dev, ctx.context(), iface, rawAddrs, parsed));
                }
            }
        }
        return out;
    }

    private record ParsedAddress(
            String raw,
            String family,
            int maxBits,
            int prefixLength,
            boolean cidrNotation,
            BigInteger hostAddress,
            BigInteger networkStart,
            BigInteger networkEnd) {
        boolean containsAddress(ParsedAddress target) {
            if (target == null || !family.equals(target.family)) {
                return false;
            }
            return target.networkStart.compareTo(networkStart) >= 0
                    && target.networkEnd.compareTo(networkEnd) <= 0;
        }
    }

    private static List<ParsedAddress> parseAddressList(Collection<String> rawStrings) {
        List<ParsedAddress> out = new ArrayList<>();
        for (String raw : rawStrings) {
            ParsedAddress p = parseAddressOrRange(raw);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    private static List<ParsedAddress> parsePolicyAddressObject(PolicyObject obj) {
        List<ParsedAddress> out = new ArrayList<>();
        Map<String, String> fields = new LinkedHashMap<>();
        for (String v : obj.values()) {
            int split = v.indexOf(": ");
            if (split >= 0) {
                String k = v.substring(0, split).strip();
                String val = v.substring(split + 2).strip();
                fields.put(k, val);
                if (Set.of("ipv4-address", "ipv6-address", "ip-netmask", "ip-range", "address").contains(k)) {
                    ParsedAddress p = parseAddressOrRange(val);
                    if (p != null) out.add(p);
                }
            } else {
                ParsedAddress p = parseAddressOrRange(v.strip());
                if (p != null) out.add(p);
            }
        }
        if (fields.containsKey("subnet4") && fields.containsKey("mask-length4")) {
            ParsedAddress p = parseAddressOrRange(fields.get("subnet4") + "/" + fields.get("mask-length4"));
            if (p != null) out.add(p);
        }
        if (fields.containsKey("subnet6") && fields.containsKey("mask-length6")) {
            ParsedAddress p = parseAddressOrRange(fields.get("subnet6") + "/" + fields.get("mask-length6"));
            if (p != null) out.add(p);
        }
        if (fields.containsKey("ip-address-first") && fields.containsKey("ip-address-last")) {
            ParsedAddress p = parseAddressOrRange(fields.get("ip-address-first") + "-" + fields.get("ip-address-last"));
            if (p != null) out.add(p);
        }
        return out;
    }

    private static ParsedAddress parseSingleAddress(String raw) {
        return parseAddressOrRange(raw);
    }

    private static boolean ipEquals(String a, String b) {
        if (Objects.equals(a, b)) return true;
        ParsedAddress pa = parseSingleAddress(a);
        ParsedAddress pb = parseSingleAddress(b);
        return pa != null && pb != null && pa.family.equals(pb.family) && pa.hostAddress.equals(pb.hostAddress);
    }

    private static ParsedAddress parseAddressOrRange(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.strip();
        if (text.contains("-")) {
            String[] parts = text.split("-", 2);
            ParsedAddress start = parseCidrOrHost(parts[0].strip());
            ParsedAddress end = parseCidrOrHost(parts[1].strip());
            if (start == null || end == null || !start.family.equals(end.family)
                    || start.hostAddress.compareTo(end.hostAddress) > 0) {
                return null;
            }
            return new ParsedAddress(
                    text, start.family, start.maxBits, start.maxBits, false,
                    start.hostAddress, start.hostAddress, end.hostAddress);
        }
        return parseCidrOrHost(text);
    }

    private static ParsedAddress parseCidrOrHost(String text) {
        String[] parts = text.split("/", -1);
        if (parts.length > 2) return null;
        String addrPart = parts[0].strip();
        boolean isIpv6 = addrPart.contains(":");
        int maxBits = isIpv6 ? 128 : 32;
        String family = isIpv6 ? "ipv6" : "ipv4";

        BigInteger host = isIpv6 ? parseIpv6Literal(addrPart) : parseIpv4Literal(addrPart);
        if (host == null) return null;

        boolean hasPrefix = parts.length == 2;
        int prefix = maxBits;
        if (hasPrefix) {
            try {
                prefix = Integer.parseInt(parts[1].strip());
                if (prefix < 0 || prefix > maxBits) return null;
            } catch (NumberFormatException invalid) {
                return null;
            }
        }

        BigInteger allOnes = BigInteger.ONE.shiftLeft(maxBits).subtract(BigInteger.ONE);
        BigInteger hostMask = prefix == maxBits
                ? BigInteger.ZERO
                : BigInteger.ONE.shiftLeft(maxBits - prefix).subtract(BigInteger.ONE);
        BigInteger netMask = allOnes.xor(hostMask);
        BigInteger netStart = host.and(netMask);
        BigInteger netEnd = netStart.or(hostMask);

        return new ParsedAddress(text, family, maxBits, prefix, hasPrefix, host, netStart, netEnd);
    }

    private static BigInteger parseIpv4Literal(String ip) {
        String[] octets = ip.split("\\.", -1);
        if (octets.length != 4) return null;
        long result = 0;
        for (String octet : octets) {
            if (!octet.matches("0|[1-9][0-9]{0,2}")) return null;
            int val = Integer.parseInt(octet);
            if (val < 0 || val > 255) return null;
            result = (result << 8) | val;
        }
        return BigInteger.valueOf(result);
    }

    private static BigInteger parseIpv6Literal(String ip) {
        if (!ip.matches("[0-9a-fA-F:.]+")) return null;
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            if (bytes.length != 16) return null;
            return new BigInteger(1, bytes);
        } catch (Exception invalid) {
            return null;
        }
    }
}
