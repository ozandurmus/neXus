package com.securityexpert.nexus.ui2.worker.confirm;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The closed command/route set the enrollment confirm may ever send
 * (contract EC-11, EC-12; {@code DEVICE_FIRST_CONTACT_COMMAND_GATE_ENTRIES.md}
 * entries 1-4, approved by the Product Owner 2026-09-14 for purpose and
 * class -- command forms {@code UNVERIFIED} until measured, bound here at
 * one site so a later correction never reopens that approval). Adding a
 * fifth entry, or a literal command/call not in one of the four lists
 * below, is a gate-document amendment, never a code change made in
 * isolation -- {@link DeviceFirstContactCommandSetTest} is the closed-set
 * proof (AC-2).
 *
 * <p>Entry 1 (Check Point identity read) carries two literal forms -- the
 * gate document's own primary/fallback pair -- as one entry, not two; the
 * closed set stays four entries, per AC-2's own count.</p>
 */
public enum DeviceFirstContactCommandSet {

    /** Gate entry 1: EC-11's identity read, Check Point Gaia over {@code ssh_exec}. Four literal
     * forms (2026-09-21 correction, DEVICE_FIRST_CONTACT_COMMAND_GATE_ENTRIES.md's own "may be
     * corrected at this one site without reopening the approval" clause): the pre-Java product's
     * own real-fleet-proven Check Point direct-SSH probe tries all four in this
     * order on a Quantum Spark/Gaia Embedded device whose landing shell and exact CLI surface
     * cannot be assumed ahead of time (AGENTS.md Check Point: "some estate devices land directly
     * in Clish; treat this as a capability, not a platform identity"). */
    CP_IDENTITY_READ(Vendor.CHECK_POINT, ContactStepKind.IDENTITY_READ,
            List.of("show version all", "show version", "clish -c \"show version all\"", "clish -c \"show version\"")),

    /** Gate entry 2: PF-1's HA/cluster role and peer naming read, Check Point. */
    CP_HA_PEER_READ(Vendor.CHECK_POINT, ContactStepKind.HA_PEER_READ,
            List.of("cphaprob stat")),

    /** Gate entry 3: EC-11's identity read, Palo Alto over {@code xml_api_call} ({@code type=op}). */
    PAN_IDENTITY_READ(Vendor.PALO_ALTO, ContactStepKind.IDENTITY_READ,
            List.of("<show><system><info/></system></show>")),

    /** Gate entry 4: PF-1's HA state and peer read, Palo Alto over {@code xml_api_call} ({@code type=op}). */
    PAN_HA_PEER_READ(Vendor.PALO_ALTO, ContactStepKind.HA_PEER_READ,
            List.of("<show><high-availability><state/></high-availability></show>"));

    private final Vendor vendor;
    private final ContactStepKind stepKind;
    private final List<String> literalForms;

    DeviceFirstContactCommandSet(Vendor vendor, ContactStepKind stepKind, List<String> literalForms) {
        this.vendor = vendor;
        this.stepKind = stepKind;
        this.literalForms = List.copyOf(literalForms);
    }

    public Vendor vendor() {
        return vendor;
    }

    public ContactStepKind stepKind() {
        return stepKind;
    }

    /** Every literal command/call string this entry may ever send -- never a prefix, never a pattern. */
    public List<String> literalForms() {
        return literalForms;
    }

    public static DeviceFirstContactCommandSet forStep(Vendor vendor, ContactStepKind stepKind) {
        for (DeviceFirstContactCommandSet entry : values()) {
            if (entry.vendor == vendor && entry.stepKind == stepKind) {
                return entry;
            }
        }
        throw new IllegalArgumentException("no gate entry for " + vendor + "/" + stepKind);
    }

    /** Every literal string any entry may send, across both vendors -- the whole closed set, flattened. */
    public static Set<String> allLiteralForms() {
        return java.util.Arrays.stream(values())
                .flatMap(entry -> entry.literalForms.stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The two device-contact steps the confirm's capability performs, in order (EC-11: identity, then HA/peer). */
    public enum ContactStepKind {
        IDENTITY_READ,
        HA_PEER_READ
    }
}
