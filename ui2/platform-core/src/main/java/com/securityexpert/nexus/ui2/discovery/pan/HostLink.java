package com.securityexpert.nexus.ui2.discovery.pan;

/**
 * VS-3/VS-4: the host relationship for a virtual-system candidate. Unlike
 * Check Point's address-searched {@code HostLink} (Missing/Ambiguous
 * possible), this relationship is structural — a virtual-system entry
 * always has exactly one parent, established by nesting alone (VS-1), so
 * there is exactly one outcome shape. {@link #hostStableIdentifier()} is
 * honest about ID-2: it is whatever {@link Serial} the parent device
 * carried, present or absent — the *link* is always established
 * structurally, even where the parent's own identifier is not.
 */
public record HostLink(Serial hostStableIdentifier) {
}
