package com.securityexpert.nexus.ui2.worker.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryCandidateRecord;
import com.securityexpert.nexus.ui2.platform.OpaqueId;

/**
 * A Symantec Management Center's device list as discovery candidates (PO 2026-09-25: "Cihazlar gelsin"). The MC's
 * uuid is the opaque stable identifier; the device's host is its address. A ProxySG (type sgos6x) is importable and
 * is then read through the Management Center; Reporter, WSS and anything else it lists are shown, not imported.
 */
public final class BlueCoatDiscoveryCandidateMapper {

    public static final String KIND_PROXYSG = "BLUECOAT_PROXYSG";

    private BlueCoatDiscoveryCandidateMapper() {
    }

    public static List<DiscoveryCandidateRecord> map(String runId, JsonNode list) {
        List<DiscoveryCandidateRecord> out = new ArrayList<>();
        if (list == null || !list.isArray()) {
            return out;
        }
        for (JsonNode d : list) {
            Optional<String> uuid = text(d, "uuid");
            if (uuid.isEmpty()) {
                continue;
            }
            String type = text(d, "type").orElse("unknown");
            boolean proxy = "sgos6x".equals(type);
            String kind = proxy ? KIND_PROXYSG : "BLUECOAT_" + type.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
            Optional<String> host = text(d, "host").map(h -> h.indexOf(':') == h.lastIndexOf(':') && h.contains(":")
                    ? h.substring(0, h.indexOf(':')) : h);
            out.add(new DiscoveryCandidateRecord(OpaqueId.random().value(), runId, "bluecoat", uuid.get(), Optional.empty(), kind,
                    text(d, "name").orElse(host.orElse(uuid.get())), host, Optional.empty(), Optional.empty(), Optional.empty(),
                    text(d, "model"), text(d, "osVersion"), text(d, "managementStatus"), proxy && host.isPresent(), Optional.empty()));
        }
        return out;
    }

    public static Map<String, Integer> outcomeSummary(List<DiscoveryCandidateRecord> records) {
        Map<String, Integer> counts = new TreeMap<>();
        records.forEach(r -> counts.merge(r.kind(), 1, Integer::sum));
        return counts;
    }

    private static Optional<String> text(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) && !n.get(f).asText().isBlank() ? Optional.of(n.get(f).asText()) : Optional.empty();
    }
}
