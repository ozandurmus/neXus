package com.securityexpert.nexus.ui2.persistence.identity;

import java.util.UUID;

public record RbacRoleRecord(
        UUID id,
        String name,
        String tokenString,
        String description,
        boolean isSystem
) {}
