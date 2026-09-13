package com.securityexpert.nexus.ui2.worker.discovery.pan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.securityexpert.nexus.ui2.discovery.pan.CandidateRow;
import com.securityexpert.nexus.ui2.discovery.pan.CandidateRowAssembler;
import com.securityexpert.nexus.ui2.discovery.pan.PairingOutcome;
import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumerationResult;
import com.securityexpert.nexus.ui2.discovery.pan.RawDeviceInput;

/**
 * AGENTS.md "Sensitive identity reporting law": renders a {@link
 * PanoramaEnumerationResult} as counts and shapes only. This class never
 * reads {@code displayName}, an address, or a stable identifier from any
 * row -- only presence/absence and enumerated outcome/state values, none of
 * which is a fixture-specific string. This is how contract §11 checks
 * 13-16 become runnable against a real Panorama; this class does not run
 * them.
 */
final class PanDiscoveryRunReport {

    private static final String NL = System.lineSeparator();

    private PanDiscoveryRunReport() {
    }

    static String render(PanoramaEnumerationResult result) {
        if (result instanceof PanoramaEnumerationResult.Refused refused) {
            return "outcome=REFUSED" + NL + "reason=" + refused.reason() + NL;
        }
        if (result instanceof PanoramaEnumerationResult.Failed failed) {
            return "outcome=FAILED" + NL
                    + "reason=" + failed.reason() + NL
                    + "request_count=" + failed.requestCount() + NL
                    + "key_disposal_outcome=" + failed.keyDisposalOutcome() + NL;
        }
        PanoramaEnumerationResult.Completed completed = (PanoramaEnumerationResult.Completed) result;
        List<RawDeviceInput> devices = completed.candidates();
        List<CandidateRow> rows = CandidateRowAssembler.assemble(devices);

        long entriesWithoutSerial = devices.stream().filter(d -> !d.stableIdentifier().isPresent()).count();

        Map<String, Integer> pairingOutcomeCounts = new LinkedHashMap<>();
        Map<String, Integer> connectionStateCounts = new LinkedHashMap<>();
        int unreciprocatedInboundClaimsTotal = 0;
        for (CandidateRow row : rows) {
            String pairingKey = switch (row.pairingOutcome()) {
                case PairingOutcome.Paired ignored -> "PAIRED";
                case PairingOutcome.NotEvaluable ne -> "NOT_EVALUABLE:" + ne.reason();
            };
            pairingOutcomeCounts.merge(pairingKey, 1, Integer::sum);
            String stateKey = row.connectionState().orElse("NONE");
            connectionStateCounts.merge(stateKey, 1, Integer::sum);
            unreciprocatedInboundClaimsTotal += row.unreciprocatedInboundClaimantSerials().size();
        }

        StringBuilder out = new StringBuilder();
        out.append("outcome=COMPLETED").append(NL);
        out.append("request_count=").append(completed.requestCount()).append(NL);
        out.append("key_disposal_outcome=").append(completed.keyDisposalOutcome()).append(NL);
        out.append("device_rows=").append(devices.size()).append(NL);
        out.append("virtual_system_rows=").append(rows.size() - devices.size()).append(NL);
        out.append("entries_without_serial=").append(entriesWithoutSerial).append(NL);
        out.append("unreciprocated_inbound_claims_total=").append(unreciprocatedInboundClaimsTotal).append(NL);
        out.append("pairing_outcome_counts=").append(pairingOutcomeCounts).append(NL);
        out.append("connection_state_counts=").append(connectionStateCounts).append(NL);
        return out.toString();
    }
}
