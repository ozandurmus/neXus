package com.securityexpert.nexus.ui2.service.pathanalysis.query;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.policy.PolicyFirewallNames;
import com.securityexpert.nexus.ui2.service.policy.PolicyQueryService;

/**
 * {@link PathPolicyCatalog} over the stored policy plane: snapshots through {@link PolicyQueryService} (its versioned
 * parse cache and single-flight load, so a path query never re-parses an unchanged snapshot), bindings from V185
 * {@code policy_target_binding}, serials from {@link PolicyFirewallNames} (the exact serial relation the Policy screen
 * uses: a serial shared by two devices proves neither).
 */
public final class StoredPathPolicyCatalog implements PathPolicyCatalog {

    private final PolicyQueryService policies;
    private final PolicyCollectionRepository collections;
    private final PolicyFirewallNames firewalls;

    public StoredPathPolicyCatalog(PolicyQueryService policies, PolicyCollectionRepository collections,
            PolicyFirewallNames firewalls) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.collections = Objects.requireNonNull(collections, "collections");
        this.firewalls = Objects.requireNonNull(firewalls, "firewalls");
    }

    @Override
    public List<PolicySnapshot.Metadata> catalog() {
        return policies.catalog();
    }

    @Override
    public Optional<PolicySnapshot> find(String policyId) {
        return policies.find(policyId);
    }

    @Override
    public List<PolicyCollectionRepository.TargetBinding> bindings(String policyId) {
        return collections.targetBindings(policyId);
    }

    @Override
    public Optional<String> panSerial(String deviceId) {
        return firewalls.serialOfDevice(deviceId);
    }
}
