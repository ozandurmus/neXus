package com.securityexpert.nexus.ui2.persistence.identity;

import java.util.Optional;

public interface DirectoryProfileRepository {
    Optional<DirectoryProfileRecord> findActiveProfile();
    DirectoryProfileRecord save(DirectoryProfileRecord record);
    // other CRUD methods if needed
}
