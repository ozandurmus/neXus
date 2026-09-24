package com.securityexpert.nexus.ui2.service.management;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jooq.Record;
import org.springframework.stereotype.Service;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;

/**
 * What a management server manages -- a Check Point MDS (domains, clusters, gateways, virtual systems) or a Palo Alto
 * Panorama (firewalls, HA pairs, virtual systems) -- from the latest finished discovery run against its own management
 * address, each item joined to the product's own device record when it is enrolled.
 *
 * <p>No device is contacted and no new command exists: the tree is the output of the gated discovery reads. A member's
 * name, HA role and collection state come from the same device summary every other screen shows (read from the member
 * itself), never from the management server, whose connection state was measured not to be a liveness signal.</p>
 *
 * <p>Names are emitted only under keys the masking advice pseudonymizes: {@code domain}, {@code cluster_member_ref},
 * {@code hostname}, {@code virtual_system}. The acknowledgement token is a hash of the match key, never the key.</p>
 */
@Service
public class ManagementTreeService {

    /** Management/log appliances a Check Point domain lists as gateway objects: never "a device missing from neXus". */
    static final Set<String> MANAGEMENT_MODEL_PREFIXES = Set.of("Smart-1");

    private final TransactionBoundary tx;
    private final DeviceRepository devices;

    public ManagementTreeService(TransactionBoundary tx, DeviceRepository devices) {
        this.tx = tx;
        this.devices = devices;
    }

    record Run(String runId, String vendor, OffsetDateTime finishedAt) {
    }

    /** The latest finished discovery run against this management server's own address, if the device is one. */
    Optional<Run> runFor(String deviceId) {
        return devices.findSummary(deviceId).flatMap(this::runFor);
    }

    Optional<Run> runFor(DeviceSummaryRecord summary) {
        Optional<DeviceSummaryRecord> d = Optional.of(summary);
        String deviceId = summary.deviceId();
        if (!"management_server".equals(d.get().role())) {
            return Optional.empty();
        }
        List<Record> run = tx.inTransaction(dsl -> dsl.fetch(
                "select r.run_id::text as run_id, r.finished_at from discovery_run r "
                        + "join endpoints e on e.address_ref = r.management_address "
                        + "where e.device_id::text = {0} and r.vendor = {1} and r.state = 'FINISHED' "
                        + "order by r.finished_at desc limit 1", deviceId, d.get().vendorHint()));
        return Optional.of(run.isEmpty() ? new Run(null, d.get().vendorHint(), null)
                : new Run(run.get(0).get("run_id", String.class), d.get().vendorHint(),
                        run.get(0).get("finished_at", OffsetDateTime.class)));
    }

    /** @return empty when the device does not exist or is not a management server */
    public Optional<Map<String, Object>> tree(String deviceId) {
        Optional<Run> run = runFor(deviceId);
        if (run.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("device_id", deviceId);
        out.put("vendor", run.get().vendor());
        out.put("run_id", run.get().runId());
        out.put("discovered_at", run.get().finishedAt());
        if (run.get().runId() == null) {
            out.put("domains", List.of());
            out.put("counts", Map.of());
            return Optional.of(out);
        }
        List<Candidate> candidates = candidates(run.get().runId());
        Map<String, Linked> linked = linked();
        Map<String, Ack> acks = acks();
        List<Map<String, Object>> domains = build(candidates, linked, acks);
        out.put("domains", domains);
        out.put("counts", counts(candidates, linked, acks));
        return Optional.of(out);
    }

    /** Gateways every enrolled management server lists that neXus does not enrol (and nobody marked not an issue). */
    public Map<String, Long> notInNexusAcrossManagers() {
        List<DeviceSummaryRecord> all = devices.listAll();
        List<DeviceSummaryRecord> managers = all.stream().filter(d -> "management_server".equals(d.role())).toList();
        if (managers.isEmpty()) {
            return Map.of("count", 0L, "managers", 0L);
        }
        Map<String, Linked> linked = linked(all);
        Map<String, Ack> acks = acks();
        long missing = 0;
        long counted = 0;
        for (DeviceSummaryRecord d : managers) {
            Optional<Run> run = runFor(d);
            if (run.isEmpty() || run.get().runId() == null) {
                continue;
            }
            counted++;
            Object n = counts(candidates(run.get().runId()), linked, acks).get("not_in_nexus");
            missing += n instanceof Number num ? num.longValue() : 0;
        }
        return Map.of("count", missing, "managers", counted);
    }

    public enum AckResult { OK, NOT_A_MANAGEMENT_SERVER, UNKNOWN_ITEM, INVALID_REASON }

    /** Marks (or, with {@code acknowledge=false}, unmarks) one listed-but-not-enrolled item as "not an issue". */
    public AckResult acknowledge(String deviceId, String token, boolean acknowledge, String reason, String actor) {
        Optional<Run> run = runFor(deviceId);
        if (run.isEmpty() || run.get().runId() == null) {
            return AckResult.NOT_A_MANAGEMENT_SERVER;
        }
        Optional<Candidate> c = candidates(run.get().runId()).stream().filter(x -> token(x.matchKey()).equals(token)).findFirst();
        if (c.isEmpty()) {
            return AckResult.UNKNOWN_ITEM;
        }
        String trimmed = reason == null ? "" : reason.strip();
        if (acknowledge && (trimmed.length() < 3 || trimmed.length() > 300)) {
            return AckResult.INVALID_REASON;
        }
        String key = c.get().matchKey();
        tx.inTransaction(dsl -> {
            dsl.execute("select set_config('app.actor_fingerprint', {0}, true)", actor);
            dsl.execute("select set_config('app.action_id', {0}, true)", "discovery_acknowledge");
            if (acknowledge) {
                return dsl.execute("insert into discovery_acknowledgement(discovery_match_key, acknowledged_by_actor_fingerprint, reason) "
                        + "values ({0}, {1}, {2}) on conflict (discovery_match_key) do update set "
                        + "acknowledged_by_actor_fingerprint = excluded.acknowledged_by_actor_fingerprint, reason = excluded.reason, "
                        + "acknowledged_at = now()", key, actor, trimmed);
            }
            return dsl.execute("delete from discovery_acknowledgement where discovery_match_key = {0}", key);
        });
        return AckResult.OK;
    }

    private List<Candidate> candidates(String runId) {
        return tx.inTransaction(dsl -> dsl.fetch(
                "select candidate_id::text as id, parent_candidate_id::text as parent, vendor, owning_domain, kind, "
                        + "display_name, cluster_reference, stable_identifier, model from discovery_candidate where run_id::text = {0}",
                runId)).stream().map(r -> new Candidate(r.get("id", String.class), r.get("parent", String.class),
                        r.get("vendor", String.class), r.get("owning_domain", String.class), r.get("kind", String.class),
                        r.get("display_name", String.class), r.get("cluster_reference", String.class),
                        r.get("stable_identifier", String.class), r.get("model", String.class)))
                .toList();
    }

    private Map<String, Linked> linked() {
        return linked(devices.listAll());
    }

    private Map<String, Linked> linked(List<DeviceSummaryRecord> all) {
        Map<String, DeviceSummaryRecord> byId = all.stream()
                .collect(Collectors.toMap(DeviceSummaryRecord::deviceId, Function.identity(), (a, b) -> a));
        Map<String, Linked> out = new HashMap<>();
        for (Record r : tx.inTransaction(dsl -> dsl.fetch(
                "select d.device_id::text as device_id, d.discovery_match_key, j.finished_at as job_finished_at from devices d "
                        + "left join lateral (select finished_at from jobs where target_device_id = d.device_id "
                        + "and job_type like '%inventory_collect' and finished_at is not null order by submitted_at desc limit 1) j on true "
                        + "where d.discovery_match_key is not null"))) {
            DeviceSummaryRecord s = byId.get(r.get("device_id", String.class));
            if (s == null) {
                continue;
            }
            out.put(r.get("discovery_match_key", String.class), new Linked(s.deviceId(), s.observedHostname().orElse(null),
                    s.observedHaRole().orElse(null), s.clusterMemberRef().orElse(null), s.enrollmentState().name(),
                    s.latestJobState().orElse(null), r.get("job_finished_at", OffsetDateTime.class)));
        }
        return out;
    }

    private Map<String, Ack> acks() {
        Map<String, Ack> out = new HashMap<>();
        for (Record r : tx.inTransaction(dsl -> dsl.fetch(
                "select discovery_match_key, reason, acknowledged_at from discovery_acknowledgement"))) {
            out.put(r.get("discovery_match_key", String.class),
                    new Ack(r.get("reason", String.class), r.get("acknowledged_at", OffsetDateTime.class)));
        }
        return out;
    }

    static String token(String matchKey) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(matchKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record Candidate(String id, String parent, String vendor, String domain, String kind, String displayName,
            String clusterReference, String stableIdentifier, String model) {
        String matchKey() {
            // Same keys as DiscoveryMatchKey (radware added 2026-09-24; before it every non-PAN vendor was keyed as Check Point).
            return switch (vendor == null ? "" : vendor) {
                case "palo_alto" -> "palo_alto|" + stableIdentifier;
                case "radware" -> "radware|" + stableIdentifier;
                default -> "check_point|" + (domain == null ? "" : domain) + "|" + stableIdentifier;
            };
        }

        boolean isCluster() {
            return kind != null && kind.endsWith("CLUSTER");
        }

        boolean isVirtualSystem() {
            return kind != null && kind.contains("VIRTUAL_SYSTEM") && !isCluster();
        }

        boolean isManagementAppliance() {
            return model != null && MANAGEMENT_MODEL_PREFIXES.stream().anyMatch(model::startsWith);
        }

        /** A device neXus would enrol: not a cluster object, not a virtual system, not a management/log appliance. */
        boolean isEnrollableDevice() {
            return !isCluster() && !isVirtualSystem() && !isManagementAppliance();
        }
    }

    record Linked(String deviceId, String hostname, String haRole, String clusterMemberRef, String enrollment,
            String jobState, OffsetDateTime jobFinishedAt) {
    }

    record Ack(String reason, OffsetDateTime at) {
    }

    static List<Map<String, Object>> build(List<Candidate> candidates, Map<String, Linked> linked, Map<String, Ack> acks) {
        Map<String, Candidate> byId = new HashMap<>();
        candidates.forEach(c -> byId.put(c.id(), c));
        Map<String, List<Candidate>> children = new HashMap<>();
        Map<String, List<Candidate>> rootsByDomain = new TreeMap<>();
        // Palo Alto: an HA pair has no object of its own -- its members share a cluster reference; a synthetic parent groups them.
        Map<String, Candidate> panPairs = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            if ("palo_alto".equals(c.vendor()) && !c.isVirtualSystem() && c.clusterReference() != null && !c.clusterReference().isBlank()
                    && (c.parent() == null || !byId.containsKey(c.parent()))) {
                Candidate pair = panPairs.computeIfAbsent(c.clusterReference(), ref -> new Candidate("pair:" + ref, null, "palo_alto",
                        c.domain(), "PALO_ALTO_HA_PAIR_CLUSTER", ref, ref, "pair:" + ref, null));
                children.computeIfAbsent(pair.id(), k -> new ArrayList<>()).add(c);
            } else if (c.parent() != null && byId.containsKey(c.parent())) {
                children.computeIfAbsent(c.parent(), k -> new ArrayList<>()).add(c);
            } else {
                rootsByDomain.computeIfAbsent(c.domain() == null ? "" : c.domain(), k -> new ArrayList<>()).add(c);
            }
        }
        for (Candidate pair : panPairs.values()) {
            rootsByDomain.computeIfAbsent(pair.domain() == null ? "" : pair.domain(), k -> new ArrayList<>()).add(pair);
        }
        List<Map<String, Object>> domains = new ArrayList<>();
        for (Map.Entry<String, List<Candidate>> d : rootsByDomain.entrySet()) {
            List<Candidate> roots = new ArrayList<>(d.getValue());
            roots.sort(Comparator.comparing((Candidate c) -> c.isCluster() ? 0 : c.isManagementAppliance() ? 2 : 1)
                    .thenComparing(Candidate::kind).thenComparing(c -> String.valueOf(c.displayName())));
            Map<String, Object> domain = new LinkedHashMap<>();
            domain.put("domain", d.getKey().isEmpty() ? null : d.getKey());
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (Candidate c : roots) {
                nodes.add(node(c, children, linked, acks));
            }
            domain.put("nodes", nodes);
            domains.add(domain);
        }
        return domains;
    }

    private static Map<String, Object> node(Candidate c, Map<String, List<Candidate>> children, Map<String, Linked> linked,
            Map<String, Ack> acks) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("kind", c.kind());
        List<Candidate> kids = new ArrayList<>(children.getOrDefault(c.id(), List.of()));
        kids.sort(Comparator.comparing(k -> String.valueOf(k.displayName())));
        Linked l = linked.get(c.matchKey());
        if (c.isCluster()) {
            // the name the enrolled members carry in the device summary, so the pseudonym equals every other screen's
            String ref = kids.stream().map(k -> linked.get(k.matchKey())).filter(x -> x != null && x.clusterMemberRef() != null)
                    .map(Linked::clusterMemberRef).findFirst().orElse(c.displayName());
            n.put("cluster_member_ref", ref);
        } else if (c.isVirtualSystem()) {
            n.put("virtual_system", c.displayName());
        } else {
            n.put("hostname", l != null && l.hostname() != null && !l.hostname().isBlank() ? l.hostname() : c.displayName());
        }
        n.put("category", c.isCluster() ? "cluster" : c.isVirtualSystem() ? "virtual_system"
                : c.isManagementAppliance() ? "management_appliance" : "device");
        n.put("model", c.model());
        n.put("candidate_id", c.id().startsWith("pair:") ? null : c.id());
        n.put("device_id", l == null ? null : l.deviceId());
        n.put("imported", l != null);
        n.put("ha_role", l == null ? null : l.haRole());
        n.put("enrollment_state", l == null ? null : l.enrollment());
        n.put("last_collection_state", l == null ? null : l.jobState());
        n.put("last_collection_at", l == null ? null : l.jobFinishedAt());
        Ack a = acks.get(c.matchKey());
        n.put("ack_token", c.isEnrollableDevice() && l == null ? token(c.matchKey()) : null);
        n.put("acknowledged", a != null);
        n.put("acknowledged_reason", a == null ? null : a.reason());
        List<Map<String, Object>> kidNodes = new ArrayList<>();
        kids.forEach(k -> kidNodes.add(node(k, children, linked, acks)));
        n.put("children", kidNodes);
        return n;
    }

    static Map<String, Object> counts(List<Candidate> candidates, Map<String, Linked> linked, Map<String, Ack> acks) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domains", candidates.stream().map(c -> c.domain() == null ? "" : c.domain()).distinct().count());
        m.put("clusters", candidates.stream().filter(Candidate::isCluster).count()
                + candidates.stream().filter(c -> "palo_alto".equals(c.vendor()) && !c.isVirtualSystem() && c.clusterReference() != null
                        && !c.clusterReference().isBlank()).map(Candidate::clusterReference).distinct().count());
        m.put("gateways", candidates.stream().filter(Candidate::isEnrollableDevice).count());
        m.put("management_appliances", candidates.stream().filter(Candidate::isManagementAppliance).count());
        m.put("virtual_systems", candidates.stream().filter(Candidate::isVirtualSystem).count());
        m.put("imported", candidates.stream().filter(c -> linked.containsKey(c.matchKey())).count());
        m.put("not_in_nexus", candidates.stream().filter(c -> c.isEnrollableDevice() && !linked.containsKey(c.matchKey())
                && !acks.containsKey(c.matchKey())).count());
        m.put("acknowledged", candidates.stream().filter(c -> c.isEnrollableDevice() && !linked.containsKey(c.matchKey())
                && acks.containsKey(c.matchKey())).count());
        return m;
    }
}
