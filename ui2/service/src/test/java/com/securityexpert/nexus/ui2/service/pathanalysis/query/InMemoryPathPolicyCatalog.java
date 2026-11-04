package com.securityexpert.nexus.ui2.service.pathanalysis.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;

/** Synthetic {@link PathPolicyCatalog}: snapshots by id, binding rows, serials; counts reads for the cache tests. */
public final class InMemoryPathPolicyCatalog implements PathPolicyCatalog {

    public final Map<String, PolicySnapshot> snapshots = new LinkedHashMap<>();
    public final Map<String, List<PolicyCollectionRepository.TargetBinding>> bindings = new HashMap<>();
    public final Map<String, String> serials = new HashMap<>();
    public final List<String> reads = new ArrayList<>();

    public InMemoryPathPolicyCatalog add(PolicySnapshot snapshot) {
        snapshots.put(snapshot.metadata().id(), snapshot);
        return this;
    }

    @Override
    public List<PolicySnapshot.Metadata> catalog() {
        reads.add("catalog");
        return snapshots.values().stream().map(PolicySnapshot::metadata).toList();
    }

    @Override
    public Optional<PolicySnapshot> find(String policyId) {
        reads.add("find:" + policyId);
        return Optional.ofNullable(snapshots.get(policyId));
    }

    @Override
    public List<PolicyCollectionRepository.TargetBinding> bindings(String policyId) {
        reads.add("bindings:" + policyId);
        return bindings.getOrDefault(policyId, List.of());
    }

    @Override
    public Optional<String> panSerial(String deviceId) {
        reads.add("serial:" + deviceId);
        return Optional.ofNullable(serials.get(deviceId));
    }
}
