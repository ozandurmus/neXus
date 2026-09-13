package com.securityexpert.nexus.ui2.discovery.pan;

import java.util.List;
import java.util.Optional;

/**
 * Synthetic fixtures shared by the §11 Band 2 property-check tests (checks
 * 7 through 12). No real serial, name, address or estate value appears
 * here — every value is invented for this test source, following cp's
 * {@code Fixtures.java} discipline.
 */
final class Fixtures {

    private Fixtures() {
    }

    private static RawDeviceInput device(String serial, String displayName, Optional<String> peerSerial,
            List<RawVirtualSystemInput> virtualSystems) {
        return new RawDeviceInput(Serial.of(serial), displayName, "", Optional.of("198.51.100.10"),
                Optional.empty(), Serial.ofOptional(peerSerial), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), virtualSystems);
    }

    private static RawDeviceInput device(String serial, String displayName, Optional<String> peerSerial) {
        return device(serial, displayName, peerSerial, List.of());
    }

    /**
     * Check 7 (AC-1): the five HA-1 to HA-4b cases, over one input set.
     *
     * <ul>
     *   <li>{@code fixture-pair-1}/{@code fixture-pair-2}: a genuine reciprocal pair (HA-1).</li>
     *   <li>{@code fixture-dangling}: a peer-serial resolving to no entry (HA-2).</li>
     *   <li>{@code fixture-self}: a peer-serial naming the candidate itself (HA-3).</li>
     *   <li>{@code fixture-onesided-claimant}/{@code fixture-onesided-target}: a one-sided claim,
     *       where the target does not name the claimant back (HA-4).</li>
     *   <li>{@code fixture-inbound-claimant}, {@code fixture-reciprocal-b}/{@code fixture-reciprocal-c}:
     *       a one-sided claim aimed at a member ({@code fixture-reciprocal-b}) of an otherwise
     *       reciprocal pair — the decisive fifth case (HA-4a/HA-4b).</li>
     * </ul>
     */
    static List<RawDeviceInput> check7Inputs() {
        return List.of(
                // Case 1: reciprocal pair.
                device("fixture-pair-1", "fixture-name-pair-1", Optional.of("fixture-pair-2")),
                device("fixture-pair-2", "fixture-name-pair-2", Optional.of("fixture-pair-1")),
                // Case 2: peer-serial resolves to no entry.
                device("fixture-dangling", "fixture-name-dangling", Optional.of("fixture-does-not-exist")),
                // Case 3: peer-serial names itself.
                device("fixture-self", "fixture-name-self", Optional.of("fixture-self")),
                // Case 4: one-sided claim — the target does not name the claimant back.
                device("fixture-onesided-claimant", "fixture-name-onesided-claimant", Optional.of("fixture-onesided-target")),
                device("fixture-onesided-target", "fixture-name-onesided-target", Optional.empty()),
                // Case 5 (decisive): fixture-inbound-claimant names fixture-reciprocal-b, but
                // fixture-reciprocal-b and fixture-reciprocal-c corroborate each other instead.
                device("fixture-inbound-claimant", "fixture-name-inbound-claimant", Optional.of("fixture-reciprocal-b")),
                device("fixture-reciprocal-b", "fixture-name-reciprocal-b", Optional.of("fixture-reciprocal-c")),
                device("fixture-reciprocal-c", "fixture-name-reciprocal-c", Optional.of("fixture-reciprocal-b")));
    }

    /** Check 9: two otherwise-identical devices whose connection-state element alone differs. */
    static List<RawDeviceInput> check9ConnectionStateVariants() {
        RawDeviceInput established = new RawDeviceInput(Serial.of("fixture-cs-1"), "fixture-name-cs-1", "",
                Optional.of("198.51.100.20"), Optional.of("2001:db8::20"), Serial.absent(),
                Optional.of("fixture-state-established"), Optional.of("fixture-timestamp-1"),
                Optional.of("fixture-cert-status-1"), Optional.of("fixture-cert-expiry-1"), List.of());
        RawDeviceInput notEstablished = new RawDeviceInput(Serial.of("fixture-cs-1"), "fixture-name-cs-1", "",
                Optional.of("198.51.100.20"), Optional.of("2001:db8::20"), Serial.absent(),
                Optional.of("fixture-state-down-equivalent"), Optional.of("fixture-timestamp-1"),
                Optional.of("fixture-cert-status-1"), Optional.of("fixture-cert-expiry-1"), List.of());
        return List.of(established, notEstablished);
    }

    /** Check 10 (AC-4): two candidates that share an identical IPv4 and IPv6 own-address value. */
    static List<RawDeviceInput> check10SharedAddressInputs() {
        return List.of(
                new RawDeviceInput(Serial.of("fixture-addr-1"), "fixture-name-addr-1", "",
                        Optional.of("198.51.100.30"), Optional.of("2001:db8::30"), Serial.absent(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of()),
                new RawDeviceInput(Serial.of("fixture-addr-2"), "fixture-name-addr-2", "",
                        Optional.of("198.51.100.30"), Optional.of("2001:db8::30"), Serial.absent(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of()));
    }

    /** Check 11 (AC-4): check 7's reciprocal pair, with both serials padded with surrounding whitespace. */
    static List<RawDeviceInput> check11WhitespacePaddedPair() {
        return List.of(
                device(" fixture-pair-1 ", "fixture-name-pair-1", Optional.of("fixture-pair-2 ")),
                device("fixture-pair-2", "fixture-name-pair-2", Optional.of(" fixture-pair-1")));
    }

    /** Check 11 (AC-4): fixture-pair-1's claimed peer serial is removed entirely from the input set. */
    static List<RawDeviceInput> check11RemovedSerial() {
        return List.of(device("fixture-pair-1", "fixture-name-pair-1", Optional.of("fixture-pair-2")));
    }

    /** Check 12 (AC-5): one device hosting two nested virtual-system entries. */
    static List<RawDeviceInput> check12NestedVirtualSystems() {
        RawVirtualSystemInput vsOne = new RawVirtualSystemInput(Serial.of("fixture-vs-1"), "fixture-name-vs-1",
                Optional.of("fixture-policy-a-1"), Optional.empty(), Optional.empty());
        RawVirtualSystemInput vsTwo = new RawVirtualSystemInput(Serial.of("fixture-vs-2"), "fixture-name-vs-2",
                Optional.empty(), Optional.of("fixture-policy-b-2"), Optional.of("fixture-policy-c-2"));
        return List.of(device("fixture-host-1", "fixture-name-host-1", Optional.empty(), List.of(vsOne, vsTwo)));
    }

    /** §11 check 8 (ID-3): every display name — device and virtual-system — replaced by one constant. */
    static List<RawDeviceInput> withEveryDisplayNameReplaced(List<RawDeviceInput> inputs, String constant) {
        return inputs.stream()
                .map(in -> new RawDeviceInput(in.stableIdentifier(), constant, in.deviceTypeMarker(),
                        in.ownIpv4Address(), in.ownIpv6Address(), in.peerSerialReference(),
                        in.connectionState(), in.connectionTimestamp(), in.certificateStatus(),
                        in.certificateExpiration(),
                        in.virtualSystems().stream()
                                .map(vs -> new RawVirtualSystemInput(vs.stableIdentifier(), constant,
                                        vs.sharedPolicyElementOne(), vs.sharedPolicyElementTwo(),
                                        vs.sharedPolicyElementThree()))
                                .toList()))
                .toList();
    }
}
