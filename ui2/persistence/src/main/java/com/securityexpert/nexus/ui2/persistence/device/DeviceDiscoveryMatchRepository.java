package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Optional;

/**
 * RD-5's single read: whether an existing {@code devices} row already
 * carries a given discovery match key (IM-10), and if so, what it
 * recorded. A narrow, single-method port -- like {@link
 * CredentialReferenceRepository} -- rather than a new method on the
 * widely-implemented {@link DeviceRepository}, so this movement's own
 * addition does not ripple through every existing {@code DeviceRepository}
 * test double.
 */
public interface DeviceDiscoveryMatchRepository {

    Optional<DeviceDiscoveryMatch> findByDiscoveryMatchKey(String discoveryMatchKey);
}
