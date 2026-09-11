package com.securityexpert.nexus.ui2.capability;

import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.OpaqueId;

/**
 * The capability-registry port (C4 §2). Implementations describe what
 * capabilities and gates exist; they never call out to a transport, an
 * LDAP adapter, or the web/worker/scheduler composition roots.
 */
public interface CapabilityRegistry {

    Optional<Capability> find(OpaqueId capabilityId);
}
