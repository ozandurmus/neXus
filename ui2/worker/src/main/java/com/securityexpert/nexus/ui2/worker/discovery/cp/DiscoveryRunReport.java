package com.securityexpert.nexus.ui2.worker.discovery.cp;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.securityexpert.nexus.ui2.discovery.cp.CandidateKind;
import com.securityexpert.nexus.ui2.discovery.cp.CandidateRow;
import com.securityexpert.nexus.ui2.discovery.cp.CandidateRowAssembler;
import com.securityexpert.nexus.ui2.discovery.cp.ClusterLink;
import com.securityexpert.nexus.ui2.discovery.cp.ConnectionTableChannelState;
import com.securityexpert.nexus.ui2.discovery.cp.HostLink;
import com.securityexpert.nexus.ui2.discovery.cp.HostResolution;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationResult;

/**
 * AGENTS.md "Sensitive identity reporting law": renders a {@link
 * ManagementPlaneEnumerationResult} as counts and shapes only. This class
 * never reads {@code displayName}, an address, an owning domain or a stable
 * identifier from any row -- {@link DiscoveryRunReportTest} asserts the
 * rendered text contains none of a fixture run's names, addresses, domains
 * or identifiers (AC-8). This is how contract §9 checks 7, 9, 11, 14-18
 * become runnable against a real server; this class does not run them.
 */
final class DiscoveryRunReport {

    private DiscoveryRunReport() {
    }

    static String render(ManagementPlaneEnumerationResult result) {
        if (result instanceof ManagementPlaneEnumerationResult.Refused refused) {
            return "outcome=REFUSED" + System.lineSeparator()
                    + "reason=" + refused.reason() + System.lineSeparator();
        }
        if (result instanceof ManagementPlaneEnumerationResult.Failed failed) {
            return "outcome=FAILED" + System.lineSeparator()
                    + "reason=" + failed.reason() + System.lineSeparator()
                    + "management_plane_request_count=" + failed.managementPlaneRequestCount() + System.lineSeparator()
                    + "disconnect_outcome=" + failed.disconnectOutcome() + System.lineSeparator();
        }
        ManagementPlaneEnumerationResult.Completed completed = (ManagementPlaneEnumerationResult.Completed) result;
        List<CandidateRow> rows = CandidateRowAssembler.assemble(completed.candidates());

        StringBuilder out = new StringBuilder();
        out.append("outcome=COMPLETED").append(System.lineSeparator());
        out.append("management_plane_request_count=").append(completed.managementPlaneRequestCount()).append(System.lineSeparator());
        out.append("disconnect_outcome=").append(completed.disconnectOutcome()).append(System.lineSeparator());
        out.append("candidates_total=").append(rows.size()).append(System.lineSeparator());

        Map<CandidateKind, Integer> byKind = new EnumMap<>(CandidateKind.class);
        Map<HostResolution, Integer> byHostResolution = new EnumMap<>(HostResolution.class);
        Map<String, Integer> byHostLink = new java.util.LinkedHashMap<>();
        Map<String, Integer> byClusterLink = new java.util.LinkedHashMap<>();
        Map<String, Integer> byChannelState = new java.util.LinkedHashMap<>();
        for (CandidateKind kind : CandidateKind.values()) {
            byKind.put(kind, 0);
        }
        for (HostResolution resolution : HostResolution.values()) {
            byHostResolution.put(resolution, 0);
        }
        for (String key : List.of("LINKED", "MISSING", "AMBIGUOUS", "NOT_APPLICABLE")) {
            byHostLink.put(key, 0);
        }
        for (String key : List.of("LINKED", "NOT_EVALUABLE")) {
            byClusterLink.put(key, 0);
        }
        for (ConnectionTableChannelState state : ConnectionTableChannelState.values()) {
            byChannelState.put(state.name(), 0);
        }
        byChannelState.put("NONE", 0);

        for (CandidateRow row : rows) {
            byKind.merge(row.kind(), 1, Integer::sum);
            byHostResolution.merge(row.hostResolution(), 1, Integer::sum);
            String hostLinkKey = row.hostLink()
                    .map(link -> switch (link) {
                        case HostLink.Linked ignored -> "LINKED";
                        case HostLink.Missing ignored -> "MISSING";
                        case HostLink.Ambiguous ignored -> "AMBIGUOUS";
                    })
                    .orElse("NOT_APPLICABLE");
            byHostLink.merge(hostLinkKey, 1, Integer::sum);
            String clusterLinkKey = switch (row.clusterLink()) {
                case ClusterLink.Linked ignored -> "LINKED";
                case ClusterLink.NotEvaluable ignored -> "NOT_EVALUABLE";
            };
            byClusterLink.merge(clusterLinkKey, 1, Integer::sum);
            String channelStateKey = row.connectionTableChannelState().map(Enum::name).orElse("NONE");
            byChannelState.merge(channelStateKey, 1, Integer::sum);
        }

        appendMap(out, "candidates_by_kind", byKind);
        appendMap(out, "host_resolution_outcomes", byHostResolution);
        appendMap(out, "host_link_outcomes", byHostLink);
        appendMap(out, "cluster_link_outcomes", byClusterLink);
        appendMap(out, "channel_state_counts", byChannelState);
        out.append("unclassified_count=").append(byKind.get(CandidateKind.UNCLASSIFIED)).append(System.lineSeparator());
        out.append("ambiguous_count=").append(byKind.get(CandidateKind.AMBIGUOUS)).append(System.lineSeparator());
        return out.toString();
    }

    private static <K> void appendMap(StringBuilder out, String label, Map<K, Integer> counts) {
        out.append(label).append('=').append(counts).append(System.lineSeparator());
    }
}
