package com.securityexpert.nexus.ui2.discovery.pan;

import java.util.List;
import java.util.Optional;

/**
 * Contract §4–§9: one device entry as read from the single authorized
 * managed-device enumeration (§3 T-2), before any relationship resolution.
 * CL-1: there is one candidate shape, not a lattice per object kind — this
 * record is written for that one shape.
 *
 * <p>{@link #deviceTypeMarker()} is CL-3: carried as-is even when empty,
 * because an empty measured value is itself a finding (CL-1) and a future
 * non-empty value would be one too (§10 U-3) — never filled in, inferred,
 * or replaced with a computed kind (CL-1, CL-3, CL-4).</p>
 *
 * <p>{@link #ownIpv4Address()} and {@link #ownIpv6Address()} are ID-4: two
 * separate locator roles, never a single overloaded address field, and
 * never compared to anything to form a relationship (ID-5).</p>
 *
 * <p>{@link #peerSerialReference()} is HA-0: the peer, carried as a serial,
 * not a name and not an address — the only thing §7 ever compares to form a
 * pair.</p>
 *
 * <p>{@link #connectionState()} is LV-0, {@link #connectionTimestamp()} is
 * CS-1, and {@link #certificateStatus()}/{@link #certificateExpiration()}
 * are CS-2 — each kept under its own name, never merged into a status, and
 * never a source of a derived liveness value (LV-1 to LV-3).</p>
 *
 * <p>{@link #virtualSystems()} is VS-1: the virtual systems this device
 * hosts, arriving nested inside this same entry, in the same response —
 * never a second call.</p>
 */
public record RawDeviceInput(
        Serial stableIdentifier,
        String displayName,
        String deviceTypeMarker,
        Optional<String> ownIpv4Address,
        Optional<String> ownIpv6Address,
        Serial peerSerialReference,
        Optional<String> connectionState,
        Optional<String> connectionTimestamp,
        Optional<String> certificateStatus,
        Optional<String> certificateExpiration,
        List<RawVirtualSystemInput> virtualSystems) {
}
