package com.securityexpert.nexus.ui2.worker.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryCandidateRecord;
import com.securityexpert.nexus.ui2.platform.OpaqueId;
import com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiManagerExecutor;

/**
 * FortiManager's devices as discovery candidates (PO 2026-09-25; FORTINET_CONTRACT.md). The serial is the opaque stable
 * identifier (never parsed); the ADOM is the domain. A standalone FortiGate is importable at the address FortiManager
 * manages it by. An HA cluster is ONE importable candidate at that address (FortiManager reaches the cluster through
 * its primary, members have no separate management address there); its members are listed under it, not importable.
 */
public final class FortinetDiscoveryCandidateMapper {

    public static final String KIND_FORTIGATE = "FORTINET_FORTIGATE";
    public static final String KIND_HA_CLUSTER = "FORTINET_HA_CLUSTER";
    public static final String KIND_HA_MEMBER = "FORTINET_HA_MEMBER";

    private FortinetDiscoveryCandidateMapper() {
    }

    public static List<DiscoveryCandidateRecord> map(String runId, List<FortiManagerExecutor.Discovered> devices) {
        List<DiscoveryCandidateRecord> out = new ArrayList<>();
        for (FortiManagerExecutor.Discovered found : devices) {
            JsonNode d = found.device();
            Optional<String> sn = text(d, "sn");
            if (sn.isEmpty()) {
                continue;
            }
            String name = text(d, "name").orElse(sn.get());
            Optional<String> address = text(d, "ip").map(a -> a.split("[\\s/]")[0]).filter(a -> !a.equals("0.0.0.0"));
            Optional<String> model = text(d, "platform_str");
            Optional<String> version = d.hasNonNull("os_ver") ? Optional.of(osVersion(d)) : Optional.empty();
            Optional<String> conn = Optional.of(switch (d.path("conn_status").asInt(-1)) {
                case 1 -> "UP";
                case 2 -> "DOWN";
                default -> "UNKNOWN";
            });
            JsonNode members = d.path("ha_slave");
            boolean cluster = d.path("ha_mode").asInt(0) != 0 && members.isArray() && members.size() > 1;
            String candidateId = OpaqueId.random().value();
            out.add(new DiscoveryCandidateRecord(candidateId, runId, "fortinet", sn.get(), Optional.of(found.adom()),
                    cluster ? KIND_HA_CLUSTER : KIND_FORTIGATE, name, address, Optional.empty(), Optional.empty(), Optional.empty(),
                    model, version, conn, address.isPresent(), Optional.empty()));
            if (cluster) {
                for (JsonNode m : members) {
                    Optional<String> msn = text(m, "sn");
                    if (msn.isEmpty()) {
                        continue;
                    }
                    String role = m.path("role").asInt(-1) == 1 ? "PRIMARY" : m.path("role").asInt(-1) == 0 ? "SECONDARY" : "UNKNOWN";
                    out.add(new DiscoveryCandidateRecord(OpaqueId.random().value(), runId, "fortinet", msn.get(), Optional.of(found.adom()),
                            KIND_HA_MEMBER, text(m, "name").orElse(msn.get()), Optional.empty(), Optional.empty(), Optional.of(name),
                            Optional.of(candidateId), model, version, Optional.of(role), false, Optional.empty()));
                }
            }
        }
        return out;
    }

    static String osVersion(JsonNode d) {
        String v = "v" + d.path("os_ver").asText("?") + "." + d.path("mr").asText("?") + "." + d.path("patch").asText("?");
        return d.hasNonNull("build") ? v + " build" + d.path("build").asText() : v;
    }

    public static Map<String, Integer> outcomeSummary(List<DiscoveryCandidateRecord> records) {
        Map<String, Integer> counts = new TreeMap<>();
        for (DiscoveryCandidateRecord r : records) {
            counts.merge(r.kind(), 1, Integer::sum);
        }
        return counts;
    }

    private static Optional<String> text(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) && !n.get(f).asText().isBlank() ? Optional.of(n.get(f).asText()) : Optional.empty();
    }
}
