package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Optional;

/** V209 {@code ha_pair_identity}: a failover pair's recorded unit serials and the state of the last comparison. */
public interface HaPairIdentityRepository {

    /**
     * Compares the pair the device belongs to with the latest serial each of its two enrolled members reported
     * ({@code device_platform_facts.serial_number}), records the pair on its first complete observation, and stores
     * the state. Empty when the device is in no pair of exactly two enrolled members of its vendor.
     */
    Optional<HaPairIdentity.State> observe(String deviceId, String actor);

    Optional<HaPairIdentity> find(String clusterMemberRef);

    HaPairIdentityRepository NONE = new HaPairIdentityRepository() {
        @Override
        public Optional<HaPairIdentity.State> observe(String deviceId, String actor) {
            return Optional.empty();
        }

        @Override
        public Optional<HaPairIdentity> find(String clusterMemberRef) {
            return Optional.empty();
        }
    };
}
