package com.securityexpert.nexus.ui2.capability;

import com.securityexpert.nexus.ui2.platform.OpaqueId;

/**
 * A closed capability description (C4 §2). The registry never accepts an
 * open-ended action/step type at runtime; every capability is one of the
 * types enumerated in this module.
 */
public record Capability(OpaqueId id, String name, GateRequirement gate) {
}
