package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;

/**
 * Reads the Virtual System interface addresses retained in the Check Point object inventory (stage 2 of the VSX VIP
 * prefix decision). Only {@code RESOLVED} {@code gateways-and-servers} inventories not older than
 * {@link #MAX_AGE} are read, only objects the collector marked complete, and only UIDs that a discovery candidate of
 * kind {@code VIRTUAL_SYSTEM_CLUSTER} or {@code STANDALONE_VIRTUAL_SYSTEM} proves. No address is logged.
 */
public final class PolicyManagementVsSource implements ManagementVsSource {

    /** Same tolerance as the policy mapper's inventory age (daily refresh plus a missed run). */
    public static final Duration MAX_AGE = Duration.ofHours(48);
    static final String INVENTORY_TYPE = "gateways-and-servers";
    private static final Set<String> VS_TYPES = Set.of("CpmiVsClusterNetobj", "CpmiVsNetobj");

    private final PolicyCollectionRepository policies;
    private final ObjectMapper mapper;
    private final Supplier<Set<String>> discoveredVsUids;

    public PolicyManagementVsSource(TransactionBoundary tx, ObjectMapper mapper) {
        this(new PolicyCollectionRepository(Objects.requireNonNull(tx, "tx")), mapper, () -> discoveredVsUids(tx));
    }

    PolicyManagementVsSource(PolicyCollectionRepository policies, ObjectMapper mapper, Supplier<Set<String>> discoveredVsUids) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.discoveredVsUids = Objects.requireNonNull(discoveredVsUids, "discoveredVsUids");
    }

    @Override
    public List<TopologyInput.ManagementVs> load(Instant builtAt) {
        Set<TopologyInput.ManagementVs> found = new LinkedHashSet<>();
        for (var ref : policies.resolvedInventories(INVENTORY_TYPE, builtAt.minus(MAX_AGE))) {
            try {
                policies.readInventory(ref.sourceId(), ref.domainRef(), INVENTORY_TYPE, document -> {
                    try {
                        found.addAll(parse(mapper.readTree(document.document())));
                    } catch (IOException invalid) {
                        throw new IllegalStateException("POLICY_INVENTORY_INVALID");
                    }
                });
            } catch (RuntimeException unreadable) {
                // A partial or unreadable inventory contributes nothing: fail closed to UNKNOWN.
                continue;
            }
        }
        if (found.isEmpty()) {
            return List.of();
        }
        Set<String> proven = discoveredVsUids.get();
        List<TopologyInput.ManagementVs> out = new ArrayList<>();
        for (TopologyInput.ManagementVs vs : found) {
            if (proven.contains(vs.uid())) {
                out.add(vs);
            }
        }
        return out;
    }

    /** Package-visible for tests: the complete VS objects of one inventory document. */
    static List<TopologyInput.ManagementVs> parse(JsonNode document) {
        List<TopologyInput.ManagementVs> out = new ArrayList<>();
        if (!"RESOLVED".equals(document.path("status").asText())) {
            return out;
        }
        for (JsonNode item : document.path("objects")) {
            JsonNode vs = item.path("vs");
            if (!VS_TYPES.contains(item.path("type").asText()) || !vs.path("complete").asBoolean(false)
                    || !item.path("uid").isTextual() || item.path("uid").textValue().isBlank()) {
                continue;
            }
            Set<String> addresses = new TreeSet<>();
            for (JsonNode iface : vs.path("interfaces")) {
                if (iface.path("address").isTextual() && iface.path("maskLength").isIntegralNumber()) {
                    addresses.add(iface.path("address").textValue() + "/" + iface.path("maskLength").intValue());
                }
            }
            if (!addresses.isEmpty()) {
                out.add(new TopologyInput.ManagementVs(item.path("uid").textValue(), addresses));
            }
        }
        return out;
    }

    private static Set<String> discoveredVsUids(TransactionBoundary tx) {
        return tx.inTransaction(dsl -> new TreeSet<>(dsl.fetch("select distinct stable_identifier from discovery_candidate "
                + "where kind in ('VIRTUAL_SYSTEM_CLUSTER', 'STANDALONE_VIRTUAL_SYSTEM')").getValues(0, String.class)));
    }
}
