package com.securityexpert.nexus.ui2.service.pathanalysis.query;

import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;

/**
 * Stage A5a: the stored policy evidence a path query binds hops to, read only. The production implementation is
 * {@link StoredPathPolicyCatalog}; tests use synthetic snapshots. Nothing here writes.
 */
public interface PathPolicyCatalog {

    /** Metadata of every stored (and local fallback) policy snapshot, targets included. */
    List<PolicySnapshot.Metadata> catalog();

    /** One snapshot through the policy parse cache (never a second parse of an unchanged row). */
    Optional<PolicySnapshot> find(String policyId);

    /** The {@code policy_target_binding} rows of one Check Point package. */
    List<PolicyCollectionRepository.TargetBinding> bindings(String policyId);

    /** The recorded serial of an enrolled Palo Alto firewall, only when it is unique both ways; empty = UNKNOWN. */
    Optional<String> panSerial(String deviceId);
}
