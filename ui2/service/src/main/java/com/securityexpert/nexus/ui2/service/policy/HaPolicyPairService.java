package com.securityexpert.nexus.ui2.service.policy;

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
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceReadRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.HaPairIdentity;
import com.securityexpert.nexus.ui2.persistence.device.HaPairIdentityRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqHaPairIdentityRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;

/**
 * Read-only HA pair policy comparison for {@code GET /api/v2/policy/ha-pairs} ({@link HaPolicyPairing}).
 *
 * <p>Pairs come from the same inventory grouping the failover screens use: enrolled Cisco ASA or FortiGate gateways
 * ({@link DeviceReadRepository#listAll()}, which already leaves out disabled and non-enrolled devices) that share one
 * {@code cluster_member_ref}, exactly two of one vendor. The pair counts as one unit only when
 * {@link HaPairIdentityRepository#find} returns the recorded identity of that vendor with state MATCH; this class only
 * reads that state, it never observes or records one. Snapshots are the stored ones
 * ({@link LocalFirewallPolicyService#asaSnapshots()} / {@link LocalFirewallPolicyService#fgtSnapshots()}), read only
 * when a pair of that vendor exists. Nothing here changes a count, a verdict or a stored row.</p>
 */
@Service
public final class HaPolicyPairService {
    /** Inventory vendor hint to snapshot vendor. */
    private static final Map<String, String> VENDOR = Map.of("cisco_asa", "ASA", "fortinet", "FGT");
    private static final Set<String> ROLES = Set.of("gateway", "firewall");

    private final LocalFirewallPolicyService snapshots;
    private final DeviceReadRepository devices;
    private final HaPairIdentityRepository identities;
    private final Clock clock;

    @Autowired
    public HaPolicyPairService(LocalFirewallPolicyService snapshots, DeviceRepository devices, TransactionBoundary tx) {
        this(snapshots, devices, new JooqHaPairIdentityRepository(tx), Clock.systemUTC());
    }

    /** Explicit wiring (tests and composition): the identity repository is read with {@code find} only. */
    public HaPolicyPairService(LocalFirewallPolicyService snapshots, DeviceReadRepository devices,
            HaPairIdentityRepository identities, Clock clock) {
        this.snapshots = snapshots; this.devices = devices; this.identities = identities; this.clock = clock;
    }

    /** One pair as found in the inventory, with the member hostnames for display (never a join key). */
    record InventoryPair(HaPolicyPairing.Pair pair, Map<String, String> names) {}

    /** The ASA and FortiGate pairs of the inventory, ordered by vendor then opaque pair reference. */
    List<InventoryPair> pairs() {
        Map<String, List<DeviceSummaryRecord>> byCluster = new TreeMap<>();
        for (DeviceSummaryRecord device : devices.listAll()) {
            if (!VENDOR.containsKey(device.vendorHint()) || !ROLES.contains(device.role()) || device.clusterMemberRef().isEmpty()) continue;
            byCluster.computeIfAbsent(device.clusterMemberRef().get(), k -> new ArrayList<>()).add(device);
        }
        List<InventoryPair> out = new ArrayList<>();
        for (var entry : byCluster.entrySet()) {
            List<DeviceSummaryRecord> members = entry.getValue().stream().sorted(Comparator.comparing(DeviceSummaryRecord::deviceId)).toList();
            if (members.size() != 2 || !members.get(0).vendorHint().equals(members.get(1).vendorHint())) continue;
            String vendorHint = members.get(0).vendorHint();
            Optional<HaPairIdentity> identity = identities.find(entry.getKey());
            boolean match = identity.filter(i -> vendorHint.equals(i.vendor()) && i.lastState() == HaPairIdentity.State.MATCH).isPresent();
            Map<String, String> names = new LinkedHashMap<>();
            for (DeviceSummaryRecord member : members) member.observedHostname().filter(n -> !n.isBlank()).ifPresent(n -> names.put(member.deviceId(), n));
            out.add(new InventoryPair(new HaPolicyPairing.Pair(opaque(entry.getKey()), VENDOR.get(vendorHint),
                    members.get(0).deviceId(), members.get(1).deviceId(), match), names));
        }
        out.sort(Comparator.comparing((InventoryPair p) -> p.pair().vendor()).thenComparing(p -> p.pair().pairRef()));
        return out;
    }

    /** The response body: every pair's rows; masking for the replay viewer is applied by {@link PolicyPrivacy}. */
    public PolicyResponse view() {
        List<InventoryPair> pairs = pairs();
        List<PolicySnapshot> stored = new ArrayList<>();
        if (pairs.stream().anyMatch(p -> p.pair().vendor().equals("ASA"))) stored.addAll(snapshots.asaSnapshots());
        if (pairs.stream().anyMatch(p -> p.pair().vendor().equals("FGT"))) stored.addAll(snapshots.fgtSnapshots());
        Map<String, Map<String, String>> names = new LinkedHashMap<>();
        for (InventoryPair p : pairs) names.put(p.pair().pairRef(), p.names());
        List<HaPolicyPairing.Result> results = HaPolicyPairing.evaluate(pairs.stream().map(InventoryPair::pair).toList(), stored);
        Instant now = clock.instant();
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (HaPolicyPairing.Outcome outcome : HaPolicyPairing.Outcome.values()) counts.put(outcome.name(), 0);
        for (HaPolicyPairing.Result result : results) {
            counts.merge(result.outcome().name(), 1, Integer::sum);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pairRef", result.pairRef());
            row.put("vendor", result.vendor());
            row.put("haContext", result.context());
            row.put("haOutcome", result.outcome().name());
            row.put("haReason", result.reason() == null ? null : result.reason().name());
            List<Map<String, Object>> members = new ArrayList<>();
            for (String deviceId : List.of(result.memberA(), result.memberB())) {
                Map<String, Object> member = new LinkedHashMap<>();
                member.put("deviceId", deviceId);
                String name = names.getOrDefault(result.pairRef(), Map.of()).get(deviceId);
                if (name != null) member.put("name", name);
                members.add(member);
            }
            row.put("memberDevices", members);
            row.put("newerSnapshotAgeSeconds", ageSeconds(result.newerCollectedAt(), now));
            row.put("olderSnapshotAgeSeconds", ageSeconds(result.olderCollectedAt(), now));
            rows.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pairCount", pairs.size());
        body.put("contextRowCount", rows.size());
        body.put("contextRowsByOutcome", counts);
        body.put("contextRows", rows);
        return new PolicyResponse(body);
    }

    /** Age of the evidence in whole seconds; null when the time is missing or unreadable (never 0). */
    static Long ageSeconds(String collectedAt, Instant now) {
        if (collectedAt == null) return null;
        try {
            return Math.max(0, Duration.between(Instant.parse(collectedAt), now).getSeconds());
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** The same opaque reference the failover screens give the pair's unit (name-based UUID of the cluster reference). */
    static String opaque(String clusterRef) {
        return UUID.nameUUIDFromBytes(clusterRef.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
