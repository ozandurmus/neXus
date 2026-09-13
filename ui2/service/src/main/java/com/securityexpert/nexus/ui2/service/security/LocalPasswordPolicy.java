package com.securityexpert.nexus.ui2.service.security;

import java.util.Optional;

/**
 * The minimum a new local password must satisfy to be storable (C3A
 * contract §4, PW-1/PW-2/PW-3). Deliberately not a policy: no complexity
 * class, expiry, history or reuse rule is defined here or anywhere else in
 * this movement (the Product Owner's 2026-09-13 deferral, §6.2).
 */
final class LocalPasswordPolicy {

    /** PW-1. */
    static final int MIN_LENGTH = 12;

    private LocalPasswordPolicy() {
    }

    /**
     * @return the closed-vocabulary violation code, or empty if storable.
     *          Every check here runs before any hash is computed (PW-2:
     *          "an empty password must never reach the hashing function at
     *          all") -- the caller must not hash first and validate after.
     */
    static Optional<String> violation(char[] newPassword, String localIdentityName) {
        if (newPassword.length < MIN_LENGTH) {
            // PW-2's empty case is length 0, already covered by PW-1's floor.
            return Optional.of("password_too_short");
        }
        if (equalsIgnoreCase(newPassword, localIdentityName)) {
            return Optional.of("password_equals_identity_name"); // PW-3
        }
        return Optional.empty();
    }

    /** Case-insensitive comparison without ever allocating a {@code String} copy of the password. */
    private static boolean equalsIgnoreCase(char[] password, String name) {
        if (password.length != name.length()) {
            return false;
        }
        for (int i = 0; i < password.length; i++) {
            if (Character.toLowerCase(password[i]) != Character.toLowerCase(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
