package com.securityexpert.nexus.ui2.persistence.identity;

import java.util.Optional;

/** Durable first-boot root identity marker; its opaque id is the only root key. */
public interface RootIdentityRepository {
    Optional<String> rootLocalIdentityId();

    void recordRootLocalIdentityId(String localIdentityId);
}
