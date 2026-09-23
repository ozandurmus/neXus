package com.securityexpert.nexus.ui2.service.management;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.jooq.Record;
import org.springframework.stereotype.Service;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * What a Check Point management server (MDS) manages, from the latest finished discovery run against its own
 * management address: domains (CMAs), then the clusters, gateways and virtual systems each domain holds, each joined
 * to the product's own device record when it was imported.
 *
 * <p>No device is contacted here and no new command exists: the tree is the output of the gated discovery commands
 * ({@code CP_DISCOVERY_MANAGEMENT_COMMAND_GATE_ENTRIES.md}). A member's HA role and collection state come from the
 * member itself (direct read), never from the management server -- its connection-state field was measured not to be a
 * liveness signal (CP discovery handover 2026-09-13, L-S1).</p>
 *
 * <p>Names are emitted only under keys the masking advice pseudonymizes: {@code domain}, {@code cluster_member_ref},
 * {@code hostname}, {@code virtual_system}.</p>
 */
@Service
public class ManagementTreeService {

    static final String VENDOR = "check_point";

    private final TransactionBoundary tx;

    public ManagementTreeService(TransactionBoundary tx) {
        this.tx = tx;
    }

    /** @return empty when the device does not exist or is not a Check Point management server */
    public Optional<Map<String, Object>> tree(String deviceId) {
        List<Record> device = tx.inTransaction(dsl -> dsl.fetch(
                "select role, vendor_hint from devices where device_id::text = {0}", deviceId));
        if (device.isEmpty() || !"management_server".equals(device.get(0).get("role", String.class))
                || !VENDOR.equalsIgnoreCase(device.get(0).get("vendor_hint", String.class))) {
            return Optional.empty();
        }
        List<Record> run = tx.inTransaction(dsl -> dsl.fetch(
                "select r.run_id::text as run_id, r.finished_at from discovery_run r "
                        + "join endpoints e on e.address_ref = r.management_address "
                        + "where e.device_id::text = {0} and r.vendor = {1} and r.state = 'FINISHED' "
                        + "order by r.finished_at desc limit 1", deviceId, VENDOR));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("device_id", deviceId);
        if (run.isEmpty()) {
            out.put("discovered_at", null);
            out.put("domains", List.of());
            out.put("counts", Map.of());
            return Optional.of(out);
        }
        String runId = run.get(0).get("run_id", String.class);
        out.put("discovered_at", run.get(0).get("finished_at", OffsetDateTime.class));
        List<Candidate> candidates = tx.inTransaction(dsl -> dsl.fetch(
                "select candidate_id::text as id, parent_candidate_id::text as parent, owning_domain, kind, display_name, "
                        + "cluster_reference, stable_identifier from discovery_candidate where run_id::text = {0}", runId))
                .stream().map(r -> new Candidate(r.get("id", String.class), r.get("parent", String.class),
                        r.get("owning_domain", String.class), r.get("kind", String.class), r.get("display_name", String.class),
                        r.get("cluster_reference", String.class), r.get("stable_identifier", String.class)))
                .toList();
        Map<String, Linked> byMatchKey = new HashMap<>();
        for (Record r : tx.inTransaction(dsl -> dsl.fetch(
                "select d.device_id::text as device_id, d.discovery_match_key, d.observed_hostname, d.observed_ha_role, "
                        + "d.enrollment_state, j.state as job_state, j.finished_at as job_finished_at from devices d "
                        + "left join lateral (select state, finished_at from jobs where target_device_id = d.device_id "
                        + "and job_type like '%inventory_collect' order by submitted_at desc limit 1) j on true "
                        + "where d.discovery_match_key like 'check_point|%'"))) {
            byMatchKey.put(r.get("discovery_match_key", String.class), new Linked(r.get("device_id", String.class),
                    r.get("observed_hostname", String.class), r.get("observed_ha_role", String.class),
                    r.get("enrollment_state", String.class), r.get("job_state", String.class),
                    r.get("job_finished_at", OffsetDateTime.class)));
        }
        out.put("domains", build(candidates, byMatchKey));
        out.put("counts", counts(candidates, byMatchKey));
        return Optional.of(out);
    }

    record Candidate(String id, String parent, String domain, String kind, String displayName, String clusterReference,
            String stableIdentifier) {
        String matchKey() {
            return "check_point|" + (domain == null ? "" : domain) + "|" + stableIdentifier;
        }

        boolean isCluster() {
            return kind != null && kind.endsWith("CLUSTER");
        }

        boolean isVirtualSystem() {
            return kind != null && kind.contains("VIRTUAL_SYSTEM") && !isCluster();
        }
    }

    record Linked(String deviceId, String hostname, String haRole, String enrollment, String jobState,
            OffsetDateTime jobFinishedAt) {
    }

    static List<Map<String, Object>> build(List<Candidate> candidates, Map<String, Linked> byMatchKey) {
        Map<String, List<Candidate>> children = new HashMap<>();
        Map<String, Candidate> byId = new HashMap<>();
        for (Candidate c : candidates) {
            byId.put(c.id(), c);
        }
        Map<String, List<Candidate>> rootsByDomain = new TreeMap<>(Comparator.nullsLast(Comparator.naturalOrder()));
        for (Candidate c : candidates) {
            if (c.parent() != null && byId.containsKey(c.parent())) {
                children.computeIfAbsent(c.parent(), k -> new ArrayList<>()).add(c);
            } else {
                rootsByDomain.computeIfAbsent(c.domain() == null ? "" : c.domain(), k -> new ArrayList<>()).add(c);
            }
        }
        List<Map<String, Object>> domains = new ArrayList<>();
        for (Map.Entry<String, List<Candidate>> d : rootsByDomain.entrySet()) {
            List<Candidate> roots = new ArrayList<>(d.getValue());
            roots.sort(Comparator.comparing((Candidate c) -> c.isCluster() ? 0 : 1).thenComparing(Candidate::kind)
                    .thenComparing(c -> String.valueOf(c.displayName())));
            Map<String, Object> domain = new LinkedHashMap<>();
            domain.put("domain", d.getKey().isEmpty() ? null : d.getKey());
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (Candidate c : roots) {
                nodes.add(node(c, children, byMatchKey));
            }
            domain.put("nodes", nodes);
            domains.add(domain);
        }
        return domains;
    }

    private static Map<String, Object> node(Candidate c, Map<String, List<Candidate>> children, Map<String, Linked> byMatchKey) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("kind", c.kind());
        List<Candidate> kids = children.getOrDefault(c.id(), List.of());
        if (c.isCluster()) {
            // the reference the imported members carry, so the pseudonym equals the one every other screen shows
            String ref = kids.stream().map(Candidate::clusterReference).filter(r -> r != null && !r.isBlank()).findFirst()
                    .orElse(c.clusterReference() != null && !c.clusterReference().isBlank() ? c.clusterReference() : c.displayName());
            n.put("cluster_member_ref", ref);
        } else if (c.isVirtualSystem()) {
            n.put("virtual_system", c.displayName());
        } else {
            Linked l = byMatchKey.get(c.matchKey());
            n.put("hostname", l != null && l.hostname() != null && !l.hostname().isBlank() ? l.hostname() : c.displayName());
        }
        Linked l = byMatchKey.get(c.matchKey());
        n.put("device_id", l == null ? null : l.deviceId());
        n.put("imported", l != null);
        n.put("ha_role", l == null ? null : l.haRole());
        n.put("enrollment_state", l == null ? null : l.enrollment());
        n.put("last_collection_state", l == null ? null : l.jobState());
        n.put("last_collection_at", l == null ? null : l.jobFinishedAt());
        List<Map<String, Object>> kidNodes = new ArrayList<>();
        kids.stream().sorted(Comparator.comparing(k -> String.valueOf(k.displayName())))
                .forEach(k -> kidNodes.add(node(k, children, byMatchKey)));
        n.put("children", kidNodes);
        return n;
    }

    static Map<String, Object> counts(List<Candidate> candidates, Map<String, Linked> byMatchKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domains", candidates.stream().map(c -> c.domain() == null ? "" : c.domain()).distinct().count());
        m.put("clusters", candidates.stream().filter(Candidate::isCluster).count());
        m.put("gateways", candidates.stream().filter(c -> !c.isCluster() && !c.isVirtualSystem()).count());
        m.put("virtual_systems", candidates.stream().filter(Candidate::isVirtualSystem).count());
        m.put("imported", candidates.stream().filter(c -> byMatchKey.containsKey(c.matchKey())).count());
        return m;
    }
}
