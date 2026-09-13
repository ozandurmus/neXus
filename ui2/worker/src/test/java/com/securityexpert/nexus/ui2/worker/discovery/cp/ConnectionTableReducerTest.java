package com.securityexpert.nexus.ui2.worker.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.discovery.cp.Address;
import com.securityexpert.nexus.ui2.discovery.cp.ClassificationFlags;
import com.securityexpert.nexus.ui2.discovery.cp.CandidateKey;
import com.securityexpert.nexus.ui2.discovery.cp.ConnectionTableChannelState;
import com.securityexpert.nexus.ui2.discovery.cp.ObjectType;
import com.securityexpert.nexus.ui2.discovery.cp.RawCandidateInput;
import com.securityexpert.nexus.ui2.platform.OpaqueId;

/**
 * §7.4 CS-3/CS-6a/CS-6b, isolated from transport and parsing (AC-6).
 */
class ConnectionTableReducerTest {

    private static final Address ESTABLISHED_ADDRESS = Address.of("198.51.100.10");
    private static final Address FAILING_ADDRESS = Address.of("198.51.100.11");
    private static final Address FLAPPING_ADDRESS = Address.of("198.51.100.12");
    private static final Address ABSENT_ADDRESS = Address.of("198.51.100.13");
    private static final Address UNMATCHED_ADDRESS = Address.of("198.51.100.99");

    private static RawCandidateInput candidateWithManagementAddress(Address address) {
        return new RawCandidateInput(
                new CandidateKey(OpaqueId.of("fixture-domain"), OpaqueId.of("fixture-" + address.value().orElseThrow())),
                ObjectType.GATEWAY, new ClassificationFlags(true, false, false), "fixture-name",
                address, address, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    void agreeingEstablishedObservationsReportEstablished() {
        List<ConnectionTableRow> observation = List.of(new ConnectionTableRow(ESTABLISHED_ADDRESS, 18190, true));
        Map<Address, ConnectionTableChannelState> result = ConnectionTableReducer.reduce(
                observation, observation, List.of(candidateWithManagementAddress(ESTABLISHED_ADDRESS)), Optional.empty());
        assertEquals(ConnectionTableChannelState.ESTABLISHED, result.get(ESTABLISHED_ADDRESS));
    }

    @Test
    void agreeingFailingObservationsReportFailingToComplete() {
        List<ConnectionTableRow> observation = List.of(new ConnectionTableRow(FAILING_ADDRESS, 18190, false));
        Map<Address, ConnectionTableChannelState> result = ConnectionTableReducer.reduce(
                observation, observation, List.of(candidateWithManagementAddress(FAILING_ADDRESS)), Optional.empty());
        assertEquals(ConnectionTableChannelState.FAILING_TO_COMPLETE, result.get(FAILING_ADDRESS));
    }

    @Test
    void disagreeingObservationsReportInTransitionNeverResolvedByRecency() {
        List<ConnectionTableRow> first = List.of(new ConnectionTableRow(FLAPPING_ADDRESS, 18190, true));
        List<ConnectionTableRow> second = List.of(new ConnectionTableRow(FLAPPING_ADDRESS, 18190, false));
        Map<Address, ConnectionTableChannelState> result = ConnectionTableReducer.reduce(
                first, second, List.of(candidateWithManagementAddress(FLAPPING_ADDRESS)), Optional.empty());
        assertEquals(ConnectionTableChannelState.IN_TRANSITION, result.get(FLAPPING_ADDRESS));
    }

    @Test
    void anAddressWithNoEntryInEitherObservationIsAbsent() {
        List<ConnectionTableRow> observation = List.of();
        Map<Address, ConnectionTableChannelState> result = ConnectionTableReducer.reduce(
                observation, observation, List.of(candidateWithManagementAddress(ABSENT_ADDRESS)), Optional.empty());
        assertEquals(ConnectionTableChannelState.ABSENT, result.get(ABSENT_ADDRESS));
    }

    /** CS-6a: an address in the connection table with no corresponding object is not reported at all. */
    @Test
    void anAddressInTheTableWithNoCorrespondingObjectIsNotReported() {
        List<ConnectionTableRow> observation = List.of(new ConnectionTableRow(UNMATCHED_ADDRESS, 18190, true));
        Map<Address, ConnectionTableChannelState> result = ConnectionTableReducer.reduce(
                observation, observation, List.of(candidateWithManagementAddress(ESTABLISHED_ADDRESS)), Optional.empty());
        assertFalse(result.containsKey(UNMATCHED_ADDRESS));
    }

    /** CS-6a: many rows per address (one established among several) still reduce to established for that observation. */
    @Test
    void manyRowsPerAddressReduceToEstablishedWhenAnyRowIsEstablished() {
        List<ConnectionTableRow> observation = List.of(
                new ConnectionTableRow(ESTABLISHED_ADDRESS, 18190, false),
                new ConnectionTableRow(ESTABLISHED_ADDRESS, 18190, true));
        Map<Address, ConnectionTableChannelState> result = ConnectionTableReducer.reduce(
                observation, observation, List.of(candidateWithManagementAddress(ESTABLISHED_ADDRESS)), Optional.empty());
        assertEquals(ConnectionTableChannelState.ESTABLISHED, result.get(ESTABLISHED_ADDRESS));
    }

    /** CS-6b: with no established row anywhere in the sample, the configured port is the fallback filter. */
    @Test
    void withNoEstablishedRowTheConfiguredPortFiltersTheSample() {
        List<ConnectionTableRow> observation = List.of(
                new ConnectionTableRow(FAILING_ADDRESS, 9999, false),
                new ConnectionTableRow(FAILING_ADDRESS, 18190, false));
        Map<Address, ConnectionTableChannelState> result = ConnectionTableReducer.reduce(
                observation, observation, List.of(candidateWithManagementAddress(FAILING_ADDRESS)), Optional.of(18190));
        assertEquals(ConnectionTableChannelState.FAILING_TO_COMPLETE, result.get(FAILING_ADDRESS));
    }
}
