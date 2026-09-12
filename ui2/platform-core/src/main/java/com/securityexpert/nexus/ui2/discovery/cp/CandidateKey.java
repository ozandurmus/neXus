package com.securityexpert.nexus.ui2.discovery.cp;

import com.securityexpert.nexus.ui2.platform.OpaqueId;

/**
 * CR-1: the candidate key is the pair (owning domain, stable identifier),
 * because U-1 (whether the stable identifier is unique across domains or
 * only within one) is unsettled — this pair is unique either way. Both
 * components are opaque {@link OpaqueId} values: never cast, never
 * trimmed of leading zeroes, never normalized, never parsed for meaning.
 */
public record CandidateKey(OpaqueId owningDomain, OpaqueId stableIdentifier) {
}
