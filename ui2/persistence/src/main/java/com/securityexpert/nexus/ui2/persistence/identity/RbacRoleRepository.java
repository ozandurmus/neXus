package com.securityexpert.nexus.ui2.persistence.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RbacRoleRepository {
    List<RbacRoleRecord> findAll();
    Optional<RbacRoleRecord> findById(UUID id);
    Optional<RbacRoleRecord> findByTokenString(String tokenString);
    void insert(RbacRoleRecord role);
    void update(RbacRoleRecord role);
    void delete(UUID id);
}
