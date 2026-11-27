package com.securityexpert.nexus.ui2.service.eventintake.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.eventintake.tls.TransportDowngradeGuard.Outcome;
import com.securityexpert.nexus.ui2.service.eventintake.tls.TransportDowngradeGuard.SourcePolicy;
import com.securityexpert.nexus.ui2.service.eventintake.tls.TransportDowngradeGuard.Transport;

class TransportDowngradeGuardTest {

    private final TransportDowngradeGuard guard = new TransportDowngradeGuard();

    @Test
    void transportEnumIsOrderedBySecurityGrade() {
        assertEquals(
                List.of(
                        Transport.HTTPS_WEBHOOK,
                        Transport.TLS_MUTUAL,
                        Transport.TLS_SERVER_ONLY,
                        Transport.PLAIN_TCP,
                        Transport.UDP_LEGACY),
                List.of(Transport.values()));
    }

    @Test
    void exhaustiveMatrixForNonAsaSourcesCoversAllRequiredAndObservedPairs() {
        Map<Transport, Map<Transport, Outcome>> expected = new EnumMap<>(Transport.class);

        expected.put(Transport.HTTPS_WEBHOOK, Map.of(
                Transport.HTTPS_WEBHOOK, Outcome.PERMITTED,
                Transport.TLS_MUTUAL, Outcome.TRANSPORT_DOWNGRADE_REFUSED,
                Transport.TLS_SERVER_ONLY, Outcome.TRANSPORT_DOWNGRADE_REFUSED,
                Transport.PLAIN_TCP, Outcome.PLAIN_TCP_PROHIBITED,
                Transport.UDP_LEGACY, Outcome.TRANSPORT_DOWNGRADE_REFUSED));

        expected.put(Transport.TLS_MUTUAL, Map.of(
                Transport.HTTPS_WEBHOOK, Outcome.TRANSPORT_DOWNGRADE_REFUSED,
                Transport.TLS_MUTUAL, Outcome.PERMITTED,
                Transport.TLS_SERVER_ONLY, Outcome.TRANSPORT_DOWNGRADE_REFUSED,
                Transport.PLAIN_TCP, Outcome.PLAIN_TCP_PROHIBITED,
                Transport.UDP_LEGACY, Outcome.TRANSPORT_DOWNGRADE_REFUSED));

        expected.put(Transport.TLS_SERVER_ONLY, Map.of(
                Transport.HTTPS_WEBHOOK, Outcome.TRANSPORT_DOWNGRADE_REFUSED,
                Transport.TLS_MUTUAL, Outcome.PERMITTED,
                Transport.TLS_SERVER_ONLY, Outcome.PERMITTED,
                Transport.PLAIN_TCP, Outcome.PLAIN_TCP_PROHIBITED,
                Transport.UDP_LEGACY, Outcome.TRANSPORT_DOWNGRADE_REFUSED));

        expected.put(Transport.PLAIN_TCP, Map.of(
                Transport.HTTPS_WEBHOOK, Outcome.INVALID_TRANSPORT_CONFIG,
                Transport.TLS_MUTUAL, Outcome.INVALID_TRANSPORT_CONFIG,
                Transport.TLS_SERVER_ONLY, Outcome.INVALID_TRANSPORT_CONFIG,
                Transport.PLAIN_TCP, Outcome.INVALID_TRANSPORT_CONFIG,
                Transport.UDP_LEGACY, Outcome.INVALID_TRANSPORT_CONFIG));

        expected.put(Transport.UDP_LEGACY, Map.of(
                Transport.HTTPS_WEBHOOK, Outcome.TRANSPORT_DOWNGRADE_REFUSED,
                Transport.TLS_MUTUAL, Outcome.PERMITTED,
                Transport.TLS_SERVER_ONLY, Outcome.PERMITTED,
                Transport.PLAIN_TCP, Outcome.PLAIN_TCP_PROHIBITED,
                Transport.UDP_LEGACY, Outcome.PERMITTED));

        for (Transport required : Transport.values()) {
            SourcePolicy policy = SourcePolicy.of(required);
            for (Transport observed : Transport.values()) {
                Outcome expectedOutcome = expected.get(required).get(observed);
                Outcome actual = guard.evaluate(policy, observed);
                assertEquals(
                        expectedOutcome,
                        actual,
                        "Mismatch for required=" + required + ", observed=" + observed);
                assertEquals(expectedOutcome == Outcome.PERMITTED, actual.isPermitted());
            }
        }
    }

    @Test
    void plainTcpIsUnconditionallyProhibitedForEveryValidRequiredTransport() {
        List<Transport> validRequired = List.of(
                Transport.HTTPS_WEBHOOK,
                Transport.TLS_MUTUAL,
                Transport.TLS_SERVER_ONLY,
                Transport.UDP_LEGACY);

        for (Transport required : validRequired) {
            assertEquals(
                    Outcome.PLAIN_TCP_PROHIBITED,
                    guard.evaluate(new SourcePolicy(required, false, false), Transport.PLAIN_TCP));
            assertEquals(
                    Outcome.PLAIN_TCP_PROHIBITED,
                    guard.evaluate(new SourcePolicy(required, true, false), Transport.PLAIN_TCP));
            assertEquals(
                    Outcome.PLAIN_TCP_PROHIBITED,
                    guard.evaluate(new SourcePolicy(required, true, true), Transport.PLAIN_TCP));
        }
    }

    @Test
    void directCiscoAsaRefusesTlsWithoutVerifiedPermitHostdownAndPermitsWhenVerified() {
        // Direct ASA without verified logging permit-hostdown: TLS_MUTUAL and TLS_SERVER_ONLY refused
        SourcePolicy asaUnverifiedMutual = new SourcePolicy(Transport.TLS_MUTUAL, true, false);
        assertEquals(Outcome.ASA_PERMIT_HOSTDOWN_UNVERIFIED, guard.evaluate(asaUnverifiedMutual, Transport.TLS_MUTUAL));
        assertEquals(Outcome.ASA_PERMIT_HOSTDOWN_UNVERIFIED, guard.evaluate(asaUnverifiedMutual, Transport.TLS_SERVER_ONLY));
        assertEquals(Outcome.TRANSPORT_DOWNGRADE_REFUSED, guard.evaluate(asaUnverifiedMutual, Transport.UDP_LEGACY));
        assertEquals(Outcome.PLAIN_TCP_PROHIBITED, guard.evaluate(asaUnverifiedMutual, Transport.PLAIN_TCP));

        SourcePolicy asaUnverifiedServerOnly = new SourcePolicy(Transport.TLS_SERVER_ONLY, true, false);
        assertEquals(Outcome.ASA_PERMIT_HOSTDOWN_UNVERIFIED, guard.evaluate(asaUnverifiedServerOnly, Transport.TLS_SERVER_ONLY));
        assertEquals(Outcome.ASA_PERMIT_HOSTDOWN_UNVERIFIED, guard.evaluate(asaUnverifiedServerOnly, Transport.TLS_MUTUAL));

        // Direct ASA with UDP_LEGACY explicitly required: UDP_LEGACY permitted, TLS still refused until verified
        SourcePolicy asaUnverifiedUdp = new SourcePolicy(Transport.UDP_LEGACY, true, false);
        assertEquals(Outcome.PERMITTED, guard.evaluate(asaUnverifiedUdp, Transport.UDP_LEGACY));
        assertTrue(guard.evaluate(asaUnverifiedUdp, Transport.UDP_LEGACY).isPermitted());
        assertEquals(Outcome.ASA_PERMIT_HOSTDOWN_UNVERIFIED, guard.evaluate(asaUnverifiedUdp, Transport.TLS_SERVER_ONLY));
        assertEquals(Outcome.ASA_PERMIT_HOSTDOWN_UNVERIFIED, guard.evaluate(asaUnverifiedUdp, Transport.TLS_MUTUAL));
        assertEquals(Outcome.TRANSPORT_DOWNGRADE_REFUSED, guard.evaluate(asaUnverifiedUdp, Transport.HTTPS_WEBHOOK));

        // Direct ASA WITH verified logging permit-hostdown: TLS permitted per anti-downgrade lock
        SourcePolicy asaVerifiedMutual = new SourcePolicy(Transport.TLS_MUTUAL, true, true);
        assertEquals(Outcome.PERMITTED, guard.evaluate(asaVerifiedMutual, Transport.TLS_MUTUAL));
        assertEquals(Outcome.TRANSPORT_DOWNGRADE_REFUSED, guard.evaluate(asaVerifiedMutual, Transport.TLS_SERVER_ONLY));
        assertEquals(Outcome.TRANSPORT_DOWNGRADE_REFUSED, guard.evaluate(asaVerifiedMutual, Transport.UDP_LEGACY));

        SourcePolicy asaVerifiedServerOnly = new SourcePolicy(Transport.TLS_SERVER_ONLY, true, true);
        assertEquals(Outcome.PERMITTED, guard.evaluate(asaVerifiedServerOnly, Transport.TLS_SERVER_ONLY));
        assertEquals(Outcome.PERMITTED, guard.evaluate(asaVerifiedServerOnly, Transport.TLS_MUTUAL));
        assertEquals(Outcome.TRANSPORT_DOWNGRADE_REFUSED, guard.evaluate(asaVerifiedServerOnly, Transport.UDP_LEGACY));

        SourcePolicy asaVerifiedUdp = new SourcePolicy(Transport.UDP_LEGACY, true, true);
        assertEquals(Outcome.PERMITTED, guard.evaluate(asaVerifiedUdp, Transport.UDP_LEGACY));
        assertEquals(Outcome.PERMITTED, guard.evaluate(asaVerifiedUdp, Transport.TLS_SERVER_ONLY));
        assertEquals(Outcome.PERMITTED, guard.evaluate(asaVerifiedUdp, Transport.TLS_MUTUAL));
    }

    @Test
    void returnsInvalidTransportConfigForNullInputsOrPlainTcpRequiredTransport() {
        assertEquals(Outcome.INVALID_TRANSPORT_CONFIG, guard.evaluate(null, Transport.TLS_MUTUAL));
        assertEquals(Outcome.INVALID_TRANSPORT_CONFIG, guard.evaluate(null, null));

        for (Transport required : Transport.values()) {
            assertEquals(
                    Outcome.INVALID_TRANSPORT_CONFIG,
                    guard.evaluate(SourcePolicy.of(required), null));
        }

        SourcePolicy nullRequired = new SourcePolicy(null, false, false);
        SourcePolicy plainTcpRequired = new SourcePolicy(Transport.PLAIN_TCP, false, false);
        SourcePolicy asaPlainTcpRequired = new SourcePolicy(Transport.PLAIN_TCP, true, true);

        for (Transport observed : Transport.values()) {
            assertEquals(Outcome.INVALID_TRANSPORT_CONFIG, guard.evaluate(nullRequired, observed));
            assertEquals(Outcome.INVALID_TRANSPORT_CONFIG, guard.evaluate(plainTcpRequired, observed));
            assertEquals(Outcome.INVALID_TRANSPORT_CONFIG, guard.evaluate(asaPlainTcpRequired, observed));
        }
        assertEquals(Outcome.INVALID_TRANSPORT_CONFIG, guard.evaluate(nullRequired, null));
    }

    @Test
    void permittedFlagAndReasonsAreWellDefinedForAllOutcomes() {
        for (Outcome outcome : Outcome.values()) {
            assertEquals(outcome == Outcome.PERMITTED, outcome.isPermitted());
            assertNotNull(outcome.reason());
            assertFalse(outcome.reason().isBlank());
        }
    }
}
