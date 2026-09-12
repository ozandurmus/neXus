package com.securityexpert.nexus.ui2.discovery.cp;

import com.securityexpert.nexus.ui2.platform.OpaqueId;

/**
 * Contract §5.2 host-linking outcomes (HL-1). Applies only to a candidate
 * resolved as {@link HostResolution#VIRTUAL_SYSTEM_HOSTED}; a candidate for
 * which host linking does not apply carries no {@code HostLink} at all
 * (see {@link CandidateRow#hostLink()}).
 */
public sealed interface HostLink {

    /** Exactly one same-domain candidate's own address matched (HL-2: recorded by stable identifier, never by address). */
    record Linked(OpaqueId hostStableIdentifier) implements HostLink {
    }

    /** Zero same-domain candidates matched. No host identifier is recorded. */
    record Missing() implements HostLink {
    }

    /** More than one same-domain candidate matched. No host identifier is recorded. */
    record Ambiguous() implements HostLink {
    }
}
