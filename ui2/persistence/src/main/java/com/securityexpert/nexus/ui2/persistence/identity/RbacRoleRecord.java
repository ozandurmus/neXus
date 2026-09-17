package com.securityexpert.nexus.ui2.persistence.identity;

import java.util.List;
import java.util.UUID;

public record RbacRoleRecord(
        UUID id,
        String name,
        String tokenString,
        String description,
        boolean isSystem,
        List<String> permissions
) {
    public RbacRoleRecord(UUID id, String name, String tokenString, String description, boolean isSystem) {
        this(id, name, tokenString, description, isSystem, List.of());
    }
}
