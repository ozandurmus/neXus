package com.securityexpert.nexus.ui2.platform;

import java.util.Optional;

/**
 * The closed role-token vocabulary (C3 §4.1). This contract does not add or
 * remove a token, only fixes that the set is closed and repository
 * -committed. No token implies another by hierarchy.
 */
public enum RoleToken {
    VIEWER("role:viewer"),
    OPERATOR("role:operator"),
    ONBOARDING_ADMIN("role:onboarding_admin"),
    BACKUP_ADMIN("role:backup_admin"),
    COMPLIANCE_ADMIN("role:compliance_admin"),
    SECURITY_ADMIN("role:security_admin");

    private final String token;

    RoleToken(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public static Optional<RoleToken> fromToken(String token) {
        for (RoleToken value : values()) {
            if (value.token.equals(token)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
