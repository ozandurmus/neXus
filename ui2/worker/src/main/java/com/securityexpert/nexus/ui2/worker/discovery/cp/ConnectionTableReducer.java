package com.securityexpert.nexus.ui2.worker.discovery.cp;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.securityexpert.nexus.ui2.discovery.cp.Address;
import com.securityexpert.nexus.ui2.discovery.cp.ConnectionTableChannelState;
import com.securityexpert.nexus.ui2.discovery.cp.RawCandidateInput;

/**
 * §7.4 CS-3/CS-6a/CS-6b, isolated from transport and parsing so it is
 * testable over hand-built rows. Never contains a numeric port literal
 * (CS-6b) -- the channel port is derived from whichever port an
 * <em>established</em> row in the sample used, falling back to {@code
 * configuredChannelPort} only when no row in the sample was established.
 */
final class ConnectionTableReducer {

    private ConnectionTableReducer() {
    }

    private enum RowState {
        NO_ROW, ESTABLISHED_ROW, ATTEMPTING_ROW
    }

    /**
     * CS-3: two observations, separated by the caller's interval, decide
     * every address's state; a disagreement is always {@code IN_TRANSITION},
     * never resolved by recency or preference. CS-6a: the result carries
     * only addresses that also appear as a candidate's management address --
     * an address that never appears at all is {@code ABSENT}, and an address
     * with no matching object is not reported (silently dropped) either way.
     */
    static Map<Address, ConnectionTableChannelState> reduce(
            List<ConnectionTableRow> firstObservation,
            List<ConnectionTableRow> secondObservation,
            List<RawCandidateInput> candidates,
            Optional<Integer> configuredChannelPort) {

        Optional<Integer> channelPort = derivedChannelPort(firstObservation, secondObservation, configuredChannelPort);

        Map<Address, RowState> first = reduceOneObservation(firstObservation, channelPort);
        Map<Address, RowState> second = reduceOneObservation(secondObservation, channelPort);

        Set<Address> candidateManagementAddresses = new HashSet<>();
        for (RawCandidateInput candidate : candidates) {
            if (candidate.managementAddress().isPresent()) {
                candidateManagementAddresses.add(candidate.managementAddress());
            }
        }

        Map<Address, ConnectionTableChannelState> result = new LinkedHashMap<>();
        for (Address address : candidateManagementAddresses) {
            RowState firstState = first.getOrDefault(address, RowState.NO_ROW);
            RowState secondState = second.getOrDefault(address, RowState.NO_ROW);
            result.put(address, combine(firstState, secondState));
        }
        return result;
    }

    private static ConnectionTableChannelState combine(RowState first, RowState second) {
        if (first != second) {
            return ConnectionTableChannelState.IN_TRANSITION;
        }
        return switch (first) {
            case NO_ROW -> ConnectionTableChannelState.ABSENT;
            case ESTABLISHED_ROW -> ConnectionTableChannelState.ESTABLISHED;
            case ATTEMPTING_ROW -> ConnectionTableChannelState.FAILING_TO_COMPLETE;
        };
    }

    /** CS-6a: many rows can name the same address; the address's row state is established if any (filtered) row is. */
    private static Map<Address, RowState> reduceOneObservation(List<ConnectionTableRow> rows, Optional<Integer> channelPort) {
        Map<Address, RowState> byAddress = new LinkedHashMap<>();
        for (ConnectionTableRow row : rows) {
            if (channelPort.isPresent() && row.port() != channelPort.get()) {
                continue;
            }
            RowState current = byAddress.getOrDefault(row.address(), RowState.NO_ROW);
            RowState observed = row.established() ? RowState.ESTABLISHED_ROW : RowState.ATTEMPTING_ROW;
            byAddress.put(row.address(), preferEstablished(current, observed));
        }
        return byAddress;
    }

    private static RowState preferEstablished(RowState a, RowState b) {
        return a == RowState.ESTABLISHED_ROW || b == RowState.ESTABLISHED_ROW ? RowState.ESTABLISHED_ROW
                : RowState.ATTEMPTING_ROW;
    }

    /** CS-6b: the port an established row was observed on, or the caller's configured value -- never a literal here. */
    private static Optional<Integer> derivedChannelPort(
            List<ConnectionTableRow> firstObservation, List<ConnectionTableRow> secondObservation,
            Optional<Integer> configuredChannelPort) {
        return java.util.stream.Stream.concat(firstObservation.stream(), secondObservation.stream())
                .filter(ConnectionTableRow::established)
                .map(ConnectionTableRow::port)
                .findFirst()
                .map(Optional::of)
                .orElse(configuredChannelPort);
    }
}
